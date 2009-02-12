package org.jboss.resteasy.test.cache;

import org.jboss.resteasy.annotations.cache.Cache;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.client.cache.CacheFactory;
import org.jboss.resteasy.test.BaseResourceTest;
import static org.jboss.resteasy.test.TestPortProvider.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class CacheTest extends BaseResourceTest
{
   private static int count = 0;

   @Path("/cache")
   public static class MyService
   {
      @GET
      @Produces("text/plain")
      @Cache(maxAge = 2)
      public String get()
      {
         count++;
         return "hello world" + count;
      }

      @Path("/etag/always/good")
      @GET
      @Produces("text/plain")
      public Response getEtagged(@Context Request request)
      {
         count++;
         Response.ResponseBuilder builder = request.evaluatePreconditions(new EntityTag("42"));
         CacheControl cc = new CacheControl();
         cc.setMaxAge(2);
         if (builder != null)
         {
            return builder.cacheControl(cc).build();
         }
         return Response.ok("hello" + count).cacheControl(cc).tag("42").build();
      }

      @Path("/etag/never/good")
      @GET
      @Produces("text/plain")
      public Response getEtaggedNeverGood(@Context Request request)
      {
         count++;
         Response.ResponseBuilder builder = request.evaluatePreconditions(new EntityTag("42"));
         if (builder != null)
         {
            return Response.serverError().build();
         }
         CacheControl cc = new CacheControl();
         cc.setMaxAge(2);
         return Response.ok("hello" + count).cacheControl(cc).tag("32").build();
      }

      @Path("/etag/always/validate")
      @GET
      @Produces("text/plain")
      public Response getValidateEtagged(@Context Request request)
      {
         count++;
         Response.ResponseBuilder builder = request.evaluatePreconditions(new EntityTag("42"));
         if (builder != null)
         {
            return builder.build();
         }
         return Response.ok("hello" + count).tag("42").build();
      }

   }

   @Path("/cache")
   public static interface MyProxy
   {
      @GET
      @Produces("text/plain")
      public String get();

      @Path("/etag/always/good")
      @GET
      @Produces("text/plain")
      public String getAlwaysGoodEtag();

      @Path("/etag/never/good")
      @GET
      @Produces("text/plain")
      public String getNeverGoodEtag();

      @Path("/etag/always/validate")
      @GET
      @Produces("text/plain")
      public String getValidateEtagged();
   }


   @Before
   public void setUp() throws Exception
   {
      addPerRequestResource(MyService.class);
   }


   @Test
   public void testProxy() throws Exception
   {
      MyProxy proxy = ProxyFactory.create(MyProxy.class, generateBaseUrl());
      CacheFactory.makeCacheable(proxy);
      String rtn = null;
      rtn = proxy.get();
      Assert.assertEquals("hello world" + 1, rtn);
      Assert.assertEquals(1, count);
      rtn = proxy.get();
      Assert.assertEquals("hello world" + 1, rtn);
      Assert.assertEquals(1, count);
      Thread.sleep(2000);
      rtn = proxy.get();
      Assert.assertEquals("hello world" + 2, rtn);
      Assert.assertEquals(2, count);
      rtn = proxy.get();
      Assert.assertEquals("hello world" + 2, rtn);
      Assert.assertEquals(2, count);

      // Test always good etag
      count = 0;
      rtn = proxy.getAlwaysGoodEtag();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(1, count);
      rtn = proxy.getAlwaysGoodEtag();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(1, count);
      Thread.sleep(2000);
      rtn = proxy.getAlwaysGoodEtag();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(2, count);
      rtn = proxy.getAlwaysGoodEtag();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(2, count);

      // Test never good etag
      count = 0;
      rtn = proxy.getNeverGoodEtag();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(1, count);
      rtn = proxy.getNeverGoodEtag();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(1, count);
      Thread.sleep(2000);
      rtn = proxy.getNeverGoodEtag();
      Assert.assertEquals("hello2", rtn);
      Assert.assertEquals(2, count);
      rtn = proxy.getNeverGoodEtag();
      Assert.assertEquals("hello2", rtn);
      Assert.assertEquals(2, count);


      // Test always validate etag
      count = 0;
      rtn = proxy.getValidateEtagged();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(1, count);
      rtn = proxy.getValidateEtagged();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(2, count);
      rtn = proxy.getValidateEtagged();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(3, count);
      rtn = proxy.getValidateEtagged();
      Assert.assertEquals("hello1", rtn);
      Assert.assertEquals(4, count);
   }

}
