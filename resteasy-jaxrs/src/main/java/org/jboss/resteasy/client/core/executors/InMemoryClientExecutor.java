package org.jboss.resteasy.client.core.executors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.jboss.resteasy.client.ClientExecutor;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jboss.resteasy.client.core.BaseClientResponse;
import org.jboss.resteasy.client.core.BaseClientResponse.BaseClientResponseStreamFactory;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@SuppressWarnings("unchecked")
public class InMemoryClientExecutor implements ClientExecutor
{
   public static String type = "CLIENT";
   public static ConcurrentHashMap<String, AtomicLong> timings = new ConcurrentHashMap<String, AtomicLong>();
   public static ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<String, AtomicLong>();
   public static long diff = 0;

   public static final void addTiming(String name, long start)
   {
//      add(timings, name, System.nanoTime() - start);
//      add(counts, name, 1);
   }

   private static void add(ConcurrentHashMap<String, AtomicLong> map, String name, long add)
   {
      AtomicLong previous = new AtomicLong(0);
      AtomicLong oldOne = map.putIfAbsent(name, previous);
      if (oldOne != null)
         previous = oldOne;
      previous.addAndGet(add);
   }

   protected Dispatcher dispatcher;
   protected URI baseUri;

   public InMemoryClientExecutor()
   {
      dispatcher = new SynchronousDispatcher(ResteasyProviderFactory
            .getInstance());
   }

   public InMemoryClientExecutor(Dispatcher dispatcher)
   {
      this.dispatcher = dispatcher;
   }

   public URI getBaseUri()
   {
      return baseUri;
   }

   public void setBaseUri(URI baseUri)
   {
      this.baseUri = baseUri;
   }

   public ClientResponse execute(ClientRequest request) throws Exception
   {
      MockHttpRequest mockHttpRequest = MockHttpRequest.create(request
            .getHttpMethod(), new URI(request.getUri()), baseUri);
      loadHttpMethod(request, mockHttpRequest);
      setBody(request, mockHttpRequest);

      final MockHttpResponse mockResponse = new MockHttpResponse();
      type = "SERVER";
      dispatcher.invoke(mockHttpRequest, mockResponse);
      type = "CLIENT";
         return createResponse(request, mockResponse);
   }

   protected BaseClientResponse createResponse(ClientRequest request,
         final MockHttpResponse mockResponse)
   {
      BaseClientResponseStreamFactory streamFactory = createStreamFactory(mockResponse);
      BaseClientResponse response = new BaseClientResponse(streamFactory);
      response.setStatus(mockResponse.getStatus());
      setHeaders(mockResponse, response);
      response.setProviderFactory(request.getProviderFactory());
      return response;
   }

   protected void setHeaders(final MockHttpResponse mockResponse,
         BaseClientResponse response)
   {
      MultivaluedMapImpl<String, String> responseHeaders = new MultivaluedMapImpl<String, String>();
      for (Entry<String, List<Object>> entry : mockResponse.getOutputHeaders().entrySet())
      {
         List<String> values = new ArrayList<String>(entry.getValue().size());
         for (Object value : entry.getValue())
         {
            values.add(value.toString());
         }
         responseHeaders.addMultiple(entry.getKey(), values);
      }
      response.setHeaders(responseHeaders);
   }

   protected BaseClientResponseStreamFactory createStreamFactory(
         final MockHttpResponse mockResponse)
   {
      return new BaseClientResponseStreamFactory()
      {
         InputStream stream;

         public InputStream getInputStream() throws IOException
         {
            if (stream == null)
            {
               stream = new ByteArrayInputStream(mockResponse.getOutput());
            }
            return stream;
         }

         public void performReleaseConnection()
         {
         }
      };
   }

   public void loadHttpMethod(ClientRequest request,
         MockHttpRequest mockHttpRequest) throws Exception
   {
      // TODO: punt on redirects, for now.
      // if (httpMethod instanceof GetMethod && request.followRedirects())
      // httpMethod.setFollowRedirects(true);
      // else httpMethod.setFollowRedirects(false);

      MultivaluedMap headers = mockHttpRequest.getHttpHeaders()
            .getRequestHeaders();
      headers.putAll(request.getHeaders());
      if (request.getBody() != null && !request.getFormParameters().isEmpty())
         throw new RuntimeException(
               "You cannot send both form parameters and an entity body");

      for (Map.Entry<String, List<String>> formParam : request
            .getFormParameters().entrySet())
      {
         String key = formParam.getKey();
         for (String value : formParam.getValue())
         {
            mockHttpRequest.getFormParameters().add(key, value);
         }
      }
   }

   private void setBody(ClientRequest request, MockHttpRequest mockHttpRequest) throws IOException
   {
      if (request.getBody() == null)
         return;

      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      MediaType bodyContentType = request.getBodyContentType();
      request.getHeaders().add(HttpHeaders.CONTENT_TYPE, bodyContentType.toString());
      MultivaluedMap mockHeaders = (MultivaluedMap) mockHttpRequest.getHttpHeaders().getRequestHeaders();
      request.writeRequestBody(mockHeaders, baos);

      mockHttpRequest.content(baos.toByteArray());
      mockHttpRequest.contentType(bodyContentType);
   }

   public Registry getRegistry()
   {
      return this.dispatcher.getRegistry();
   }

}
