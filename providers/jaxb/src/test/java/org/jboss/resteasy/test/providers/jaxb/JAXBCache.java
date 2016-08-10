package org.jboss.resteasy.test.providers.jaxb;

import org.jboss.resteasy.core.ExceptionAdapter;
import org.jboss.resteasy.plugins.providers.jaxb.i18n.LogMessages;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A JAXBCache.
 *
 * @author <a href="ryan@damnhandy.com">Ryan J. McDonough</a>
 * @version $Revision:$
 */
public final class JAXBCache
{

   /**
    *
    */
   private static JAXBCache instance = new JAXBCache();

   /**
    *
    */
   private ConcurrentHashMap<Object, JAXBContext> contextCache = new ConcurrentHashMap<Object, JAXBContext>();

   /**
    * Create a new JAXBCache.
    */
   private JAXBCache()
   {

   }

   /**
    * FIXME Comment this
    *
    * @return
    */
   public static JAXBCache instance()
   {
      return instance;
   }

   /**
    * FIXME Comment this
    *
    * @param classes
    * @return
    */
   public JAXBContext getJAXBContext(Class<?>... classes)
   {
      JAXBContext context = contextCache.get(classes);
      if (context == null)
      {
         try
         {
            context = JAXBContext.newInstance(classes);
         }
         catch (JAXBException e)
         {
            throw new ExceptionAdapter(e);
         }
         contextCache.putIfAbsent(classes, context);
      }
      LogMessages.LOGGER.debug("Locating JAXBContext for package: " + classes[0]);
      return context;
   }

   /**
    * FIXME Comment this
    *
    * @param packageNames
    * @return
    */
   public JAXBContext getJAXBContext(String... packageNames)
   {
      String contextPath = buildContextPath(packageNames);
      LogMessages.LOGGER.debug("Locating JAXBContext for packages: " + contextPath);
      // FIXME This was the original call causing an infinitive recursive loop.
      // However I don't know how to fix it, but this method is not used currently
      // so instead of fixing it modified it to return a null and not going into
      // recursive loop for now.

      // return getJAXBContext(contextPath, null);
      return null;
   }

   /**
    * FIXME Comment this
    *
    * @param packageNames
    * @return
    */
   private String buildContextPath(String[] packageNames)
   {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < packageNames.length; i++)
      {
         b.append(packageNames[i]);
         if (i != (packageNames.length - 1))
         {
            b.append(":");
         }
      }
      return b.toString();
   }

}