package org.jboss.resteasy.plugins.server.reactor.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.ThreadLocalResteasyProviderFactory;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedJaxrsServer;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.util.EmbeddedServerHelper;
import org.jboss.resteasy.util.PortProvider;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.tcp.TcpServer;

import javax.net.ssl.SSLContext;
import javax.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A server adapter built on top of <a
 * href='https://github.com/reactor/reactor-netty'>reactor-netty</a>.  Similar
 * to the adapter built on top of netty4, this adapter will ultimately run on
 * Netty rails.  Leveraging reactor-netty brings 3 main benefits, which are:
 *
 * 1. Reactor-netty's HttpServer + handle(req, resp) API is a little closer
 * match to how a normal HTTP server works.  Basically, it should be easier for
 * an HTTP web server person to maintain compared to a raw Netty
 * implementation.  However, this assumes you don't have to delve into
 * reactor-netty!
 * 2. Reactor Netty puts a <a href='https://projectreactor.io/'>reactor</a>
 * facade on top of Netty.  The Observable+Iterable programming paradigm is
 * more general purpose than Netty's IO-centric Channel concept.  In theory, it
 * should be more beneficial to learn:)
 * 3. When paired with a Netty-based client (e.g. the JAX-RS client powered by
 * reactor-netty), the threadpool can be efficiently shared between the client
 * and the server.
 *
 */
public class ReactorNettyJaxrsServer implements EmbeddedJaxrsServer<ReactorNettyJaxrsServer> {

   private static final Logger log = LoggerFactory.getLogger(ReactorNettyJaxrsServer.class);

   protected String hostname = null;
   protected int configuredPort = PortProvider.getPort();
   protected int runtimePort = -1;
   protected ResteasyDeployment deployment;
   protected String root = "";
   protected SecurityDomain domain;
   private Duration idleTimeout;
   private SSLContext sslContext;
   private final EmbeddedServerHelper serverHelper = new EmbeddedServerHelper();

   private DisposableServer server;

   @Override
   public ReactorNettyJaxrsServer deploy() {
      return this;
   }

   @Override
   public ReactorNettyJaxrsServer start() {
      log.info("Starting RestEasy Reactor-based server!");
      serverHelper.checkDeployment(deployment);

      //EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
      String appPath = serverHelper.checkAppDeployment(deployment);
      if (appPath != null && (root == null || "".equals(root))) {
         setRootResourcePath(appPath);
      }

      final HttpServer svrBuilder =
          HttpServer.create()
              .tcpConfiguration(this::configure)
              .port(configuredPort);

      if (hostname != null && !hostname.trim().isEmpty()) {
         svrBuilder.host(hostname);
      }

      final Handler handler = new Handler();

      server = svrBuilder
          .handle(handler::handle)
          .bindNow();
      runtimePort = server.port();

      return this;
   }

   /**
    * Calling this method will block the current thread.
    */
   public void startAndBlock() {
       start();
       server.onDispose().block();
   }

   class Handler {

      private final Mono<InputStream> empty = Mono.just(new InputStream() {
         @Override
         public int read() {
            return -1;  // end of stream
         }
      });

      Publisher<Void> handle(final HttpServerRequest req, final HttpServerResponse resp) {

         if (1 == 2) {
            final NettyOutbound o =
                resp.send(req.receive().retain().doOnEach(b -> log.trace("got some bytes!")));
            return o.then()
                .doFinally(s -> log.trace("Request processing finished with: {}", s));
         }

         final ResteasyUriInfo info = new ResteasyUriInfo(req.uri(), root);

         // aggregate (and maybe? asInputStream) reads the entire request body into memory (direct?)
         // Can we stream it in some way?
         // https://stackoverflow.com/a/51801335/2071683 but requires a thread.  Isn't using a thread
         // per request even if from the elastic pool a big problem???  I mean we are trying to reduce
         // threads!
         // I honestly don't know what the Netty4 adapter is doing here.  When
         // I try to send a large body it says "request payload too large".  I
         // don't know if that's configurable or not..
        

         // This is a subscription tied to the completion writing the response.
         final MonoProcessor<Void> completionMono = MonoProcessor.create();

         return req.receive()
             .aggregate()
             .asInputStream()
             .switchIfEmpty(empty)
             .flatMap(body -> {
                log.trace("Body read!");
                resp.withConnection(conn -> {
                   log.trace("{}", conn.channel().id());
                });

                // These next 2 classes provide the main '1-way bridges' between reactor-netty and RestEasy.
                final ReactorNettyHttpResponse resteasyResp =
                    new ReactorNettyHttpResponse(req.method(), resp, completionMono);

                final ReactorNettyHttpRequest resteasyReq =
                    new ReactorNettyHttpRequest(
                        info,
                        req,
                        body,
                        resteasyResp,
                        (SynchronousDispatcher) deployment.getDispatcher()
                    );

                // This is what actually kick RestEasy into action.
                ResteasyProviderFactory defaultInstance = ResteasyProviderFactory.getInstance();
                if (defaultInstance instanceof ThreadLocalResteasyProviderFactory) {
                   ThreadLocalResteasyProviderFactory.push(deployment.getProviderFactory());
                }

                deployment.getDispatcher().invoke(resteasyReq, resteasyResp);

                if (defaultInstance instanceof ThreadLocalResteasyProviderFactory) {
                   ThreadLocalResteasyProviderFactory.pop();
                }

                if (!resteasyReq.getAsyncContext().isSuspended()) {
                   log.trace("suspended finish called!");
                   try {
                      resteasyResp.close();
                   } catch (IOException e) {
                      throw new RuntimeException(e);
                   }
                }

                final Mono<Void> actualMono =
                    resteasyReq.timeout != null ? completionMono.timeout(resteasyReq.timeout) : completionMono;

                log.trace("Returning completion signal mono from main Flux.");
                return actualMono
                    .doOnCancel(() -> log.trace("Subscription cancelled"))
                    .doOnSubscribe(s -> log.trace("Subscription on completion mono: {}", s))
                    .doFinally(s -> log.trace("The completion mono completed with: {}", s));
             }).onErrorResume(t -> {
                final Mono<Void> sendMono;
                if (t instanceof TimeoutException) {
                   sendMono = resp.status(503).send();
                } else {
                   sendMono = resp.status(500).sendString(Mono.just(t.getLocalizedMessage())).then();
                }
                sendMono.subscribe(completionMono);
                return completionMono;
             }).doFinally(s -> log.trace("Request processing finished with: {}", s))
             .doOnSubscribe(s -> log.trace("handle subscription: {}", s));
      }
   }

   @Override
   public void stop()
   {
      runtimePort = -1;
      server.disposeNow();
      if (deployment != null) {
         deployment.stop();
      }
   }

   @Override
   public ResteasyDeployment getDeployment() {
      if (deployment == null)
      {
         deployment = new ResteasyDeploymentImpl();
      }
      return deployment;
   }

   @Override
   public ReactorNettyJaxrsServer setDeployment(ResteasyDeployment deployment)
   {
      this.deployment = deployment;
      return this;
   }

   @Override
   public ReactorNettyJaxrsServer setPort(int port) {
      this.configuredPort = port;
      return this;
   }

   public int getPort() {
      return runtimePort > 0 ? runtimePort : configuredPort;
   }

   @Override
   public ReactorNettyJaxrsServer setHostname(String hostname) {
      this.hostname = hostname;
      return this;
   }

   public String getHostname() {
      return hostname;
   }

   @Override
   public ReactorNettyJaxrsServer setRootResourcePath(String rootResourcePath)
   {
      root = rootResourcePath;
      if (root != null && root.equals("/")) {
         root = "";
      } else if (!root.startsWith("/")) {
         root = "/" + root;
      }
      return this;
   }

   @Override
   public ReactorNettyJaxrsServer setSecurityDomain(SecurityDomain sc)
   {
      this.domain = sc;
      return this;
   }

   public ReactorNettyJaxrsServer setIdleTimeout(final Duration idleTimeout) {
      this.idleTimeout = idleTimeout;
      return this;
   }

   public ReactorNettyJaxrsServer setSSLContext(SSLContext sslContext)
   {
      this.sslContext = sslContext;
      return this;
   }

   private TcpServer configure(final TcpServer baseServer) {
      if (idleTimeout != null) {
         return baseServer.doOnConnection(conn -> {
            final long idleNanos = idleTimeout.toNanos();
            // TODO, why can't I use reactor-netty methods for this??
            conn.channel().pipeline().addFirst("idleStateHandler", new IdleStateHandler(0, 0, idleNanos, TimeUnit.NANOSECONDS));
            conn.channel().pipeline().addAfter("idleStateHandler", "idleEventHandler", new ChannelDuplexHandler() {
               @Override
               public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                  if (evt instanceof IdleStateEvent) {
                     IdleStateEvent e = (IdleStateEvent) evt;
                     if (e.state() == IdleState.ALL_IDLE) {
                        ctx.close();
                     }
                  }
               }
            });
         });
      }
      return baseServer;
   }
}
