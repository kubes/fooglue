package com.igfoo.springutils;

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.io.ContextResource;
import org.springframework.core.io.Resource;

/**
 * An extension to the ReloadableResourceBundleMessageSource class that allows
 * specifying the bundles using a pattern.  This allows us to use the same 
 * convention for resource bundles, where new bundles are automatically added.
 */
public class PatternReloadableResourceBundleMessageSource
  extends ReloadableResourceBundleMessageSource {

  private final static Logger LOG = LoggerFactory
    .getLogger(PatternReloadableResourceBundleMessageSource.class);

  public void setResources(Resource[] resources) {

    Set<String> bundleSet = new LinkedHashSet<String>();
    for (Resource resource : resources) {

      // ignore non-existent resources and those not loaded from the file
      // system, classpath, or servlet container
      if (!resource.exists() || !(resource instanceof ContextResource)) {
        continue;
      }

      // are we looking at an xml or properties file
      String filename = resource.getFilename();
      boolean isProps = filename.endsWith(".properties");
      boolean isXml = filename.endsWith(".xml");

      // has to be one of the two
      if (isProps || isXml) {

        // basenames are /messages.xml, so ignore their localized
        // counterparts, /messages_en.xml, they will be loaded later
        String[] nameExtension = StringUtils.split(filename, ".");
        if (nameExtension != null && nameExtension.length == 2) {
          String name = nameExtension[0];
          if (name.charAt(name.length() - 4) == '_') {
            continue;
          }
        }

        // basename will be from root path to end of file name without the
        // extension, example /web-inf/messages.xml would be translated to
        // /web-inf/messages
        String path = ((ContextResource)resource).getPathWithinContext();
        int extLen = isProps ? 11 : 4;
        String bundle = path.substring(0, path.length() - extLen);

        bundleSet.add(bundle);
        LOG.info("Added resource bundle: " + bundle);
      }
    }

    // add the bundles to the parent reloadable message source
    int numBundles = bundleSet.size();
    if (numBundles > 0) {
      String[] bundles = bundleSet.toArray(new String[numBundles]);
      super.setBasenames(bundles);
    }
  }
}
