package com.igfoo.springutils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContext;

import org.apache.commons.io.filefilter.AndFileFilter;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.OrFileFilter;
import org.apache.commons.io.filefilter.PrefixFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.web.context.ConfigurableWebApplicationContext;

/**
 * <p>An ApplicationContextInitializer implementation for Spring MVC apps that
 * allows easy and flexible configurations of properties used to configure the
 * application.</p>
 * 
 * <p>By default the initializer looks under the application root WEB-INF for
 * a folder structure as follows.  You can override the default location using
 * a servlet init param called propertyRoot.  The propertyRoot path is relative
 * from the WEB-INF folder.  If the propertyRoot folder doesn't exist or is not
 * readable, the entire property loading process exits.</p>
 * 
 * <pre>
 * WEB-INF/
 *  props/
 *    base/
 *    envs/
 *    hosts/
 *    users/
 * </pre>
 * 
 * <p>The initializer start with properties from the base folder.  By default
 * all .properties and *props.xml files will be loaded.  You can override the 
 * property file suffixes to match using an init-param named propertyFileSuffix.
 * The base folder is considered the core properties folder for the application
 * that holds the default properties.</p>
 * 
 * <p>Next system properties will be searched for an "environment" property and 
 * if found properties will be loaded from the envs folder.  Properties must
 * have the environment name as a prefix and the property file names as suffix.
 * For example a file for an environment named prod would be prod_props.xml.</p>
 * 
 * <p>The hosts folder is then searched for properties files starting with the
 * ip address or hostname of the server.  For example 192.168.1.1.props.xml.</p>
 * 
 * <p>And finally the users folder is search for properties starting with the
 * current user name as defined in the user.name system property.</p>
 * 
 * <p>Each stage of property loading override any previous properties with the
 * same name.  For instance, environment properties can be set that override the
 * base properties for a staging and production environments and the user 
 * properties allows specific users to have development properties that over 
 * base properties.  All properties should be uniquely named.  The order in 
 * which properties are loaded within a stage is deterministic by undefined.</p>
 * 
 * <p>Each stage of loading will recurse through file system.  Folders can be
 * nested any level deep and properties files will still be loaded as long as
 * they match the rules for that folder.  None of the stage is required and if
 * a folder, for instance envs or hosts, doesn't exist, that stage will simply
 * be ignored.</p>
 * 
 * @author Dennis Kubes
 */
public class PropertyWebappContextInitializer
  implements ApplicationContextInitializer<ConfigurableWebApplicationContext> {

  private final static Logger LOG = LoggerFactory
    .getLogger(PropertyWebappContextInitializer.class);

  private void collectFiles(List<File> collector, File root, FileFilter filter) {

    if (root.exists() && root.isDirectory() && root.canRead()) {

      File[] children = null;
      if (filter != null) {
        children = root.listFiles(filter);
      }
      else {
        children = root.listFiles();
      }

      if (children != null) {
        for (File child : children) {
          if (child.isFile() && child.canRead()) {
            collector.add(child);
          }
          else {
            // recurse for directories
            collectFiles(collector, child, filter);
          }
        } // end child files
      }
    }
  }

  @Override
  public void initialize(ConfigurableWebApplicationContext context) {

    ConfigurableEnvironment environment = context.getEnvironment();
    MutablePropertySources propertySources = environment.getPropertySources();

    // get the property root, default to props folder under WEB-INF
    ServletContext servletContext = context.getServletContext();
    String propertyRoot = servletContext.getInitParameter("propertyRoot");
    if (StringUtils.isBlank(propertyRoot)) {
      propertyRoot = "/WEB-INF/props";
    }
    String rootPath = servletContext.getRealPath(propertyRoot);
    File propertyRootDir = new File(rootPath);

    // get the property suffix or default to common properties files
    String suffixes = servletContext.getInitParameter("propertyFileSuffix");
    String[] propertySuffixes = StringUtils.isNotBlank(suffixes) ? StringUtils
      .split(suffixes, ", ") : new String[]{
      "props.xml", ".properties"
    };

    // only configure properties if there is a root property directory
    if (propertyRootDir.exists() && propertyRootDir.isDirectory()) {

      List<File> propertyFiles = new ArrayList<File>();
      SuffixFileFilter propsFilter = new SuffixFileFilter(propertySuffixes);
      IOFileFilter dirFilter = DirectoryFileFilter.DIRECTORY;

      // get base property files, recurse into directories
      collectFiles(propertyFiles, new File(propertyRootDir, "base"),
        new OrFileFilter(dirFilter, propsFilter));

      // collect property files for environment get any file matching the
      // environment prefix and properties suffix, recurse into directories
      String envProp = System.getProperty("environment");
      if (StringUtils.isNotBlank(envProp)) {
        File envPropsRoot = new File(propertyRootDir, "envs");
        if (envPropsRoot.exists() && envPropsRoot.isDirectory()) {
          PrefixFileFilter envPrefixFilter = new PrefixFileFilter(envProp);
          collectFiles(propertyFiles, envPropsRoot, new OrFileFilter(dirFilter,
            new AndFileFilter(envPrefixFilter, propsFilter)));
        }
      }

      // collect property files for ip address or hostname get any file matching
      // the ip address or hostname prefix and properties suffix recurse into
      // directories
      try {

        // get the ip address and system hostname
        InetAddress inetAddress = InetAddress.getLocalHost();
        PrefixFileFilter ipOrHostFilter = new PrefixFileFilter(new String[]{
          inetAddress.getHostAddress(), inetAddress.getHostName()
        });

        File hostPropsRoot = new File(propertyRootDir, "hosts");
        if (hostPropsRoot.exists() && hostPropsRoot.isDirectory()) {
          collectFiles(propertyFiles, hostPropsRoot, new OrFileFilter(
            dirFilter, new AndFileFilter(ipOrHostFilter, propsFilter)));
        }
      }
      catch (Exception e) {
        // ignore exceptions for ip address and hostname
      }

      // collect property files for specific users, get any file matching
      // the user name prefix and properties suffix recurse into directories
      String userProp = System.getProperty("user.name");
      if (StringUtils.isNotBlank(userProp)) {
        File userPropsRoot = new File(propertyRootDir, "users");
        if (userPropsRoot.exists() && userPropsRoot.isDirectory()) {
          PrefixFileFilter envPrefixFilter = new PrefixFileFilter(envProp);
          collectFiles(propertyFiles, userPropsRoot, new OrFileFilter(
            dirFilter, new AndFileFilter(envPrefixFilter, propsFilter)));
        }
      }

      // load the properties files found into the spring property sources
      if (propertyFiles.size() > 0) {
        for (File propertyFile : propertyFiles) {
          try {
            propertySources.addFirst(new ResourcePropertySource(
              new FileSystemResource(propertyFile)));
            LOG.info("Loaded properties file: %s", propertyFile.getPath());
          }
          catch (IOException e) {
            LOG.error("Error loading properties file: %s",
              propertyFile.getPath());
          }
        }
      }
    }

  }
}
