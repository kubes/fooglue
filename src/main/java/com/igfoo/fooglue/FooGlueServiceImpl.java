package com.igfoo.fooglue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.io.Resource;

public class FooGlueServiceImpl
  implements MessageSourceAware, FooGlueService {

  private final static Logger LOG = LoggerFactory
    .getLogger(FooGlueServiceImpl.class);

  private MessageSource messageSource;
  private Resource[] configResources;
  private String rootDir;
  private String globalConfig = "fooglue.fgc";
  private String aliasTagStart = "{{";
  private String aliasTagEnd = "}}";
  private String propertyTagStart = "[[";
  private String propertyTagEnd = "]]";
  private String assetHost;
  private boolean requireIdForAssets = false;

  // config file and asset file reloading
  private long reloadInterval = 2000;
  private PollingReloader reloader;
  private AtomicBoolean active = new AtomicBoolean(false);
  private Map<String, Long> fileModTimes = new ConcurrentHashMap<String, Long>();
  private Set<String> configSet = new LinkedHashSet<String>();
  private Set<String> assetSet = new LinkedHashSet<String>();
  private Map<String, Set<String>> assetsToConfigs = new HashMap<String, Set<String>>();
  private Map<String, String> idToConfig = new HashMap<String, String>();

  // caches and asset maps
  private String cacheDir;
  private boolean cacheAssets = false;
  private boolean minifyAssets = false;
  private Map<String, Map> assets = new ConcurrentHashMap<String, Map>();
  private Map<String, String> aliasesCache = new ConcurrentHashMap<String, String>();
  private Map<String, List> scriptsCache = new ConcurrentHashMap<String, List>();
  private Map<String, List> metaCache = new ConcurrentHashMap<String, List>();
  private Map<String, List> linksCache = new ConcurrentHashMap<String, List>();
  private Map<String, String> titleCache = new ConcurrentHashMap<String, String>();

  /**
   * A continuous looping thread that polls the file system for changes to both
   * configuration files and asset files, css and javascript. Starts execution
   * on startup and ends on shutdown.
   */
  private class PollingReloader
    extends Thread {

    @Override
    public void run() {

      // holder for any files that were monitored and have not been removed
      // from the filesystem and need to be removed from monitoring
      List<String> removed = new ArrayList<String>();

      while (active.get()) {

        // loop through the files checking for updated modified times
        for (Entry<String, Long> fileModEntry : fileModTimes.entrySet()) {

          // get the filename and last modified time
          String filePath = fileModEntry.getKey();
          long fileLastMod = fileModEntry.getValue();

          // if the file exists on the file system and it has been modified
          File checkFile = new File(filePath);
          if (checkFile.exists() && (checkFile.lastModified() > fileLastMod)) {

            // if the file is an asset file, get the configs it is part of, else
            // it is a config file, add it to be reloaded
            Set<String> configsToReload = new LinkedHashSet<String>();
            if (assetSet.contains(filePath)) {
              configsToReload = assetsToConfigs.get(filePath);
            }
            else {
              configsToReload.add(filePath);
            }

            // check for global reloading
            boolean globalReload = false;
            for (String configToReload : configsToReload) {
              if (StringUtils.equalsIgnoreCase(configToReload, globalConfig)) {
                globalReload = true;
                break;
              }
            }

            // do a global reload or individual reloads of configuration files
            // even if asset files changed, we still are reloading their configs
            if (globalReload) {
              LOG.info("Global config changed, reloading all asset configs");
              loadAllAssetConfigFiles();
            }
            else {
              for (String configToReload : configsToReload) {
                LOG.info("Reloading: " + configToReload);
                loadAssetConfig(new File(configToReload));
              }
            }
          }
          else if (!checkFile.exists()) {
            // the file existed previously but has now been removed
            removed.add(filePath);
          }
        }

        // remove any assets from monitoring that have been removed from the
        // filesystem, no need to keep polling for files that aren't there
        // TODO: more fine grained removal from caches
        for (String assetOrConfig : removed) {
          fileModTimes.remove(assetOrConfig);
        }
        removed.clear();

        // sleep and then do it all over again
        try {
          Thread.sleep(reloadInterval);
        }
        catch (InterruptedException e) {
          // continue if interrupted
        }
      }
    }
  }

  /**
   * Replaces an alias name with its interpolated value.
   * 
   * @param alias The alias to replace.
   * 
   * @return The interpolated alias value.
   */
  private String resolveAlias(String alias) {

    // remove the start and end alias tags and resolve the alias to its value
    if (StringUtils.isNotBlank(alias)
      && StringUtils.startsWith(alias, aliasTagStart)
      && StringUtils.endsWith(alias, aliasTagEnd)) {
      String aliasKey = StringUtils.removeStart(alias, aliasTagStart);
      aliasKey = StringUtils.removeEnd(aliasKey, aliasTagEnd);
      return aliasesCache.get(aliasKey);
    }

    // alias doesn't have a value, return the original alias
    return alias;
  }

  /**
   * Replaces all aliases for values in the input Map and returns a map with the
   * key to interpolated alias values or original values if no alias exists.
   * 
   * @param keyVals The Map of keys and values to check and replace aliases.
   * 
   * @return A Map of keys to interpolated alias values or original values if
   * the value didn't contain an alias.
   */
  private Map<String, String> resolveAliases(Map<String, String> keyVals) {

    // loop through the key value map checking for aliases, replacing any
    // aliases in values if they are found
    Map<String, String> replaced = new LinkedHashMap<String, String>();
    for (Entry<String, String> keyVal : keyVals.entrySet()) {
      String value = resolveAlias(keyVal.getValue());
      replaced.put(keyVal.getKey(), value);
    }
    return replaced;
  }

  /**
   * Returns a key value Map from a JSON node. This is used in custom asset
   * configuration, such as meta tags.
   * 
   * @param node The node to extract key value pairs from.
   * 
   * @return A Map containing the key value attribute pairs.
   */
  private Map<String, String> getAttributes(JsonNode node) {
    Map<String, String> attrMap = new LinkedHashMap<String, String>();
    for (String fieldname : JSONUtils.getFieldNames(node)) {
      String value = JSONUtils.getStringValue(node, fieldname);
      if (StringUtils.isNotBlank(value)) {
        attrMap.put(fieldname, value);
      }
    }
    return attrMap;
  }

  /**
   * Replaces a property with its interpolated value if one exists. If no
   * property exists then the original name is returned.
   * 
   * @param alias The alias to replace.
   * 
   * @param property The property to
   * @param locale
   * @return The interpolated alias value.
   */
  private String resolveProperty(String property, Locale locale) {

    // remove the start and end property tags and resolve the property to its
    // value
    if (StringUtils.isNotBlank(property)
      && StringUtils.startsWith(property, propertyTagStart)
      && StringUtils.endsWith(property, propertyTagEnd)) {
      String propertyName = StringUtils.removeStart(property, propertyTagStart);
      propertyName = StringUtils.removeEnd(propertyName, propertyTagEnd);

      // try to resolve the property, if no property exists then default back
      // to the original input
      try {
        return messageSource.getMessage(propertyName, new Object[0], locale);
      }
      catch (NoSuchMessageException e) {
        return property;
      }
    }

    // short circuit, no property tags, no reason to check for property
    return property;
  }

  /**
   * Returns the source of the asset as a string. This is useful when you want
   * to embed a script or stylesheet source inside a web page.
   * 
   * @param assetPath The filesystem path to the asset to embed.
   * 
   * @return The source of the asset
   */
  private String getAssetSource(String assetPath) {

    // read the source of the asset if it exists and we can read it
    if (StringUtils.isNotBlank(assetPath)) {
      File asset = new File(rootDir + File.separator + assetPath);
      if (asset.exists() && asset.canRead()) {
        try {
          return FileUtils.readFileToString(asset);
        }
        catch (IOException e) {
          LOG.error("Error reading asset source: " + asset.getPath(), e);
        }
      }
    }

    // no source found
    return null;
  }

  /**
   * Adds the asset to monitoring allowing it to be reloaded upon changes.
   * 
   * @param assetPath The asset file path.
   * @param configPath The config file path
   */
  private void monitorAsset(String assetPath, String configPath) {

    // only need to add it if it doesn't already exist in monitoring
    if (!fileModTimes.containsKey(assetPath)) {

      File asset = new File(assetPath);
      if (asset.exists()) {

        // asset last modified time
        long lastMod = asset.lastModified();
        fileModTimes.put(assetPath, lastMod);

        // add the asset to the assets set
        assetSet.add(assetPath);

        // map the asset to its configs, allowing all configs that contain it to
        // be reloaded when the asset changes
        if (assetsToConfigs.containsKey(assetPath)) {
          Set<String> configs = assetsToConfigs.get(assetPath);
          configs.add(configPath);
        }
        else {
          LinkedHashSet<String> configs = new LinkedHashSet<String>();
          configs.add(configPath);
          assetsToConfigs.put(assetPath, configs);
        }
      }
    }
  }

  /**
   * Minify and cache asset source files. Allows scripts and stylesheets to be
   * changed on the fly and new versions to have new names and be loaded
   * immediately by users.
   * 
   * @param fieldMap The asset key values Map.
   * 
   * @param isStyleSheet Is the asset a stylesheet or a script.
   */
  private void compressAndCache(Map<String, String> fieldMap,
    boolean isStyleSheet) {

    // if the cache directory does exist, try to create it, if we can't then
    // just return, nothing to do
    File cacheRoot = new File(cacheDir);
    if (!cacheRoot.exists()) {
      if (!cacheRoot.mkdirs()) {
        return;
      }
    }

    // cache out the file by crc32 of its contents
    String srcAttr = isStyleSheet ? "href" : "src";
    String srcPath = fieldMap.get(srcAttr);
    if (srcPath == null) {
      return;
    }
    File rawFile = new File(rootDir, srcPath);
    if (rawFile.exists()) {
      try {

        // get the raw bytes of the asset source and create a crc value to
        // identify unique contents
        byte[] rawBytes = FileUtils.readFileToByteArray(rawFile);
        byte[] cachedBytes = rawBytes;
        CRC32 crc32 = new CRC32();
        crc32.update(rawBytes);
        long crcVal = crc32.getValue();
        int lastSep = StringUtils.lastIndexOf(srcPath, ".");
        String prefix = StringUtils.substring(srcPath, 0, lastSep);
        String dotExt = StringUtils.substring(srcPath, lastSep);
        String cachedSrc = prefix + "-" + crcVal + dotExt;

        // we are choosing to not overwrite any file that exists in the cache
        // if a file changes from min to not it will need to be removed from
        // the cache and regenerated. If we don't do this then every load of
        // a file across every page could potentially rewrite files
        File cacheFile = new File(cacheRoot, cachedSrc);
        if (!cacheFile.exists()) {

          // don't minify files that are named something.min.(js|css), anything
          // with the min extension is assumed to already be minified, don't
          // want to do it twice
          boolean alreadyMinified = StringUtils.contains(cachedSrc, "min.")
            || StringUtils.contains(cachedSrc, "min-");

          // if we are minifiying try to compress the file, otherwise we are
          // just copying the original file
          if (minifyAssets && !alreadyMinified) {
            ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
              if (isStyleSheet) {
                FooGlueCompressor.compressStyleSheet(bais, baos);
              }
              else {
                FooGlueCompressor.compressJavaScript(bais, baos);
              }
              cachedBytes = baos.toByteArray();
            }
            catch (Exception e) {
              // do nothing, keep the original content
              LOG.error("Error during minification: " + srcAttr, e);
            }
          }

          // write the file out to the cache, either minified or not, the parent
          // directories of the file will be created in the cache dir if they
          // don't already exist
          FileUtils.writeByteArrayToFile(cacheFile, cachedBytes);
        }

        // change the source of the file to the cache source
        fieldMap.put(srcAttr, cacheDir + cachedSrc);
      }
      catch (IOException e) {
        // do nothing, keep the original path vs the cached path
        LOG.error("Error caching: " + srcAttr, e);
      }
    }
  }

  /**
   * <p>Loads a single fooglue asset configuration.</p>
   * 
   * <p>Assets are the css, scripts, and metadata for a single entry inside of a
   * single configuration file. A config file can contain multiple entries each
   * of which would be loaded separately. Assets are linked to a unique id setup
   * in the config file for each entry.</p>
   * 
   * @param asset The asset entry.
   * @param configPath The configuration file path for this asset.
   * @param isGlobal Is this a global asset configuration.
   */
  private void loadAsset(JsonNode asset, String configPath, boolean isGlobal) {

    // map to hold the configuration for the url or global
    Map<String, Object> curAssets = new LinkedHashMap<String, Object>();

    // get all ids, a single asset config can have multiple ids it is linked to
    List<String> ids = new ArrayList<String>();
    if (asset.has("id")) {
      JsonNode idNode = asset.get("id");
      ids = JSONUtils.getStringValues(idNode);
    }

    // link the ids to the config, each id is unique
    for (String id : ids) {
      idToConfig.put(id, configPath);
    }

    // if no ids and not global and not named then ignore
    if (ids.isEmpty() && !isGlobal) {
      return;
    }

    // add the title
    String title = JSONUtils.getStringValue(asset, "title");
    if (StringUtils.isNotBlank(title)) {
      curAssets.put(FooGlueConstants.TITLE, resolveAlias(title));
    }

    // loop through the meta tag configurations
    if (asset.has("meta")) {
      List<Map<String, String>> metas = new ArrayList<Map<String, String>>();
      for (JsonNode meta : asset.get("meta")) {
        Map<String, String> fieldMap = resolveAliases(getAttributes(meta));
        if (fieldMap.size() > 0) {
          metas.add(fieldMap);
        }
      }

      // add meta list to asset map if anything to add
      if (metas.size() > 0) {
        curAssets.put(FooGlueConstants.METAS, metas);
      }
    }

    // loop through the scripts
    if (asset.has("scripts")) {
      List<Map<String, String>> scripts = new ArrayList<Map<String, String>>();
      for (JsonNode script : asset.get("scripts")) {

        // scripts can be shorthand of just the href, and can be an alias
        Map<String, String> fieldMap = null;
        if (script instanceof TextNode) {

          String src = ((TextNode)script).asText();
          src = resolveAlias(src);
          fieldMap = new LinkedHashMap<String, String>();
          fieldMap.put("type", "text/javascript");
          fieldMap.put("src", src);
        }
        else {
          fieldMap = resolveAliases(getAttributes(script));
        }

        // minify and cache the script if possible, if not use the original
        String scriptPath = fieldMap.get("src");
        if (fieldMap.size() > 0 && StringUtils.isNotBlank(scriptPath)) {
          try {
            scripts.add(fieldMap);
            if (cacheAssets) {
              compressAndCache(fieldMap, false);
            }
          }
          catch (Exception e) {
            LOG.error("Error caching/minifying, using original script: "
              + scriptPath);
          }

          // monitor the script for changes
          monitorAsset(scriptPath, configPath);
        }
      }

      // add script list to asset map if anything to add
      if (scripts.size() > 0) {
        curAssets.put(FooGlueConstants.SCRIPTS, scripts);
      }
    }

    // loop through the stylesheets
    if (asset.has("links")) {
      List<Map<String, String>> links = new ArrayList<Map<String, String>>();
      for (JsonNode link : asset.get("links")) {

        // scripts can be shorthand of just the href, and can be an alias
        Map<String, String> fieldMap = null;
        if (link instanceof TextNode) {

          String href = ((TextNode)link).asText();
          href = resolveAlias(href);
          fieldMap = new LinkedHashMap<String, String>();
          fieldMap.put("rel", "stylesheet");
          fieldMap.put("type", "text/css");
          fieldMap.put("href", href);
        }
        else {
          fieldMap = resolveAliases(getAttributes(link));
        }

        // minify and cache the stylesheet if possible, if not use the original
        String stylePath = fieldMap.get("href");
        if (fieldMap.size() > 0 && stylePath != null) {
          try {
            links.add(fieldMap);
            if (cacheAssets) {
              compressAndCache(fieldMap, true);
            }
          }
          catch (Exception e) {
            LOG.error("Error caching/minifying, using original stylesheet: "
              + stylePath);
          }

          // monitor the stylesheet for changes
          monitorAsset(stylePath, configPath);
        }
      }

      // add links list to asset map if anything to add
      if (links.size() > 0) {
        curAssets.put(FooGlueConstants.LINKS, links);
      }
    }

    // add the current assets as either global or for a specific path
    if (curAssets.size() > 0) {

      // setup as global and id based resources
      if (isGlobal) {

        // if global and caching we have to clear all caches because we don't
        // know what the global values touch
        assets.put(FooGlueConstants.GLOBAL, curAssets);
        if (cacheAssets) {
          scriptsCache.clear();
          metaCache.clear();
          linksCache.clear();
          titleCache.clear();
        }
      }
      else {
        for (String id : ids) {

          // if not a global config we can just clear the cache for the single
          // id or ids contained in the config
          assets.put(id, curAssets);
          if (cacheAssets) {
            scriptsCache.remove(id);
            metaCache.remove(id);
            linksCache.remove(id);
            titleCache.remove(id);
          }
        }
      }
    }
  }

  /**
   * Loads a single fooglue configuration file. This clears any cache that is
   * associated with the ids in this configuration.
   * 
   * @param configFile The configuration file to load.
   */
  private void loadAssetConfig(File configFile) {

    // ignore if the config file doesn't exist
    String configPath = configFile.getPath();
    if (!configFile.exists()) {
      LOG.warn("Config file doesn't exist: " + configPath + ", ignoring");
      return;
    }

    // set the last modified for the file before parsing in case of errors
    long lastModified = configFile.lastModified();
    fileModTimes.put(configPath, lastModified);

    // add the to the config file set
    configSet.add(configPath);

    try {

      // processing global file or content file
      String filename = configFile.getName();
      boolean isGlobal = StringUtils.equals(filename, globalConfig);
      ObjectMapper mapper = new ObjectMapper();
      JsonNode root = mapper.readValue(configFile, JsonNode.class);

      // global then load aliases
      if (isGlobal) {
        aliasesCache.putAll(getAttributes(root.get("aliases")));
      }

      // config files can hold multiple entries
      if (root instanceof ArrayNode) {
        for (JsonNode asset : root) {
          loadAsset(asset, configPath, false);
        }
      }
      else {
        loadAsset(root, configPath, isGlobal);
      }
    }
    catch (Exception e) {
      LOG.error("Error parsing config: " + configPath, e);
    }
  }

  /**
   * Load or reload all asset configuration files.
   */
  private void loadAllAssetConfigFiles() {

    // loop through resources to load asset configs if any exist
    if (configResources != null) {
      for (Resource configResource : configResources) {
        try {
          File assetConfig = configResource.getFile();
          LOG.info("Loading asset config: " + assetConfig.getPath());
          loadAssetConfig(assetConfig);
        }
        catch (Exception e) {
          // do nothing, continue with other files
        }
      }
    }
  }

  private String getCacheKey(String id, Locale locale) {
    return id + "_" + locale.getLanguage() + "_" + locale.getCountry();
  }

  /**
   * Creates and returns a script tag.
   * 
   * @param scriptAttrs The attributes of the script tag.
   * @param locale The current locale used to resolve properties.
   * 
   * @return A script tag.
   */
  private String createScriptTag(Map<String, String> scriptAttrs, Locale locale) {

    StringBuilder scriptTagBuilder = new StringBuilder();

    String type = scriptAttrs.get("type");
    String src = resolveProperty(scriptAttrs.get("src"), locale);
    scriptTagBuilder.append("<script");
    if (StringUtils.isNotBlank(type)) {
      scriptTagBuilder.append(" type=\"" + type + "\"");
    }
    if (StringUtils.isNotBlank(src)) {

      // if script is local, set the hostname, allows cookieless domains, and
      // cdn serving
      if (StringUtils.startsWith(src, "/")) {
        src = assetHost + src;
      }
      scriptTagBuilder.append(" src=\"" + src + "\"");
    }
    scriptTagBuilder.append(">");

    String embedScript = resolveProperty(scriptAttrs.get("embed"), locale);
    if (StringUtils.isNotBlank(embedScript)) {
      scriptTagBuilder.append("\n" + getAssetSource(embedScript) + "\n");
    }
    scriptTagBuilder.append("</script>");

    return scriptTagBuilder.toString();
  }

  /**
   * Creates and returns a meta tag.
   * 
   * @param metaAttrs The attributes of the meta tag.
   * @param locale The current locale used to resolve properties.
   * 
   * @return A meta tag.
   */
  private String createMetaTag(Map<String, String> metaAttrs, Locale locale) {

    StringBuilder metaTagBuilder = new StringBuilder();
    metaTagBuilder.append("<meta");
    for (Entry<String, String> metaAttr : metaAttrs.entrySet()) {
      String key = resolveProperty(metaAttr.getKey(), locale);
      String value = resolveProperty(metaAttr.getValue(), locale);
      if (StringUtils.isNotBlank(key)) {
        metaTagBuilder.append(" " + key + "=\"");
      }
      metaTagBuilder.append(value + "\"");
    }
    metaTagBuilder.append(" />");
    return metaTagBuilder.toString();
  }

  /**
   * Creates and returns a link tag.
   * 
   * @param linkAttrs The attributes of the link tag.
   * @param locale The current locale used to resolve properties.
   * 
   * @return A link tag.
   */
  private String createLinkTag(Map<String, String> linkAttrs, Locale locale) {

    StringBuilder linkTagBuilder = new StringBuilder();

    linkTagBuilder.append("<link");
    for (Entry<String, String> linkAttr : linkAttrs.entrySet()) {
      String key = resolveProperty(linkAttr.getKey(), locale);
      String value = resolveProperty(linkAttr.getValue(), locale);
      if (StringUtils.isNotBlank(key)) {

        // if the href is local, set the hostname
        if (StringUtils.equals(key, "href")
          && StringUtils.startsWith(value, "/")) {
          value = assetHost + value;
        }
        linkTagBuilder.append(" " + key + "=\"");
      }
      linkTagBuilder.append(value + "\"");
    }
    linkTagBuilder.append(" />");

    return linkTagBuilder.toString();
  }

  public FooGlueServiceImpl() {

  }

  public FooGlueServiceImpl(String rootDir, Resource[] configResources) {
    this.rootDir = rootDir;
    setConfigResources(configResources);
  }

  /**
   * Initialize the fooglue service. Loads all of the configuration resources.
   * Starts up the changes monitoring.
   */
  public synchronized void initialize() {

    // setup the cache directory
    if (StringUtils.isBlank(cacheDir)) {

      // setup the cache under a temp directory if a directory isn't specified
      File tempCache = new File(FileUtils.getTempDirectory(), "_fg_cache_");
      boolean goodCacheDir = tempCache.exists();

      // if the cache directory doesn't exist and you can't create it, then
      // turn caching off
      if (!goodCacheDir) {
        goodCacheDir = tempCache.mkdirs();
        if (!goodCacheDir) {
          LOG.error("Couldn't create cache directory, turing caching off");
          cacheAssets = false;
          minifyAssets = false;
        }
      }

      // set the cache directory if good
      if (goodCacheDir) {
        cacheDir = tempCache.getPath();
      }
    }

    // load all asset config files
    if (configResources != null && configResources.length > 0) {
      loadAllAssetConfigFiles();
    }

    // activate the service
    active.set(true);

    // start the reloading thread if we have a reload interval
    if (reloadInterval > 0) {
      reloader = new PollingReloader();
      reloader.setDaemon(true);
      reloader.start();
    }
  }

  /**
   * Shutdown the fooglue service. Clears all assets and configs. Clears all
   * caches. Delete the cache directory from the file system.
   */
  public synchronized void shutdown() {

    // set active to false to stop the reloader
    active.set(false);

    // clear the assets and configs
    fileModTimes.clear();
    configSet.clear();
    assetSet.clear();
    assetsToConfigs.clear();
    idToConfig.clear();
    assets.clear();

    // clear the caches
    aliasesCache.clear();
    scriptsCache.clear();
    metaCache.clear();
    linksCache.clear();
    titleCache.clear();

    // quietly remove the cache directory
    FileUtils.deleteQuietly(new File(cacheDir));
  }

  /**
   * Reinitializes the service by shutting down and then restarting.
   */
  public synchronized void reinitialize() {
    shutdown();
    initialize();
  }

  /**
   * Returns a list of the script tags for the id. If includeGlobal is true then
   * the global script tags are also included.
   * 
   * @param id The unique id matching an id in a fooglue config file.
   * @param locale The current locale, used to resolve properties.
   * @param includeGlobal Include global script tags.
   * 
   * @return The list of script tags for the id and locale.
   */
  public List<String> getScriptTagsForId(String id, Locale locale,
    boolean includeGlobal) {

    // get the cache key from id and locale
    String cacheKey = getCacheKey(id, locale);

    // check the cache first
    if (scriptsCache.containsKey(cacheKey)) {
      return scriptsCache.get(cacheKey);
    }

    // not in cache create a new list and populate with the global scripts
    List<String> scriptTags = new ArrayList<String>();

    // get the global and id assets
    Map globalAssets = (Map)assets.get(FooGlueConstants.GLOBAL);
    Map idAssets = (Map)assets.get(id);
    if (requireIdForAssets && idAssets == null) {
      return scriptTags;
    }

    // add the global scripts
    if (includeGlobal && globalAssets != null) {
      List<Map> globalScripts = (List<Map>)globalAssets
        .get(FooGlueConstants.SCRIPTS);
      if (globalScripts != null && globalScripts.size() > 0) {
        for (Map<String, String> scriptAttrs : globalScripts) {
          String scriptTag = createScriptTag(scriptAttrs, locale);
          scriptTags.add(scriptTag);
        }
      }
    }

    // add the scripts for the path
    if (idAssets != null) {
      List<Map> idScripts = (List<Map>)idAssets.get(FooGlueConstants.SCRIPTS);
      if (idScripts != null && idScripts.size() > 0) {
        for (Map<String, String> scriptAttrs : idScripts) {
          String scriptTag = createScriptTag(scriptAttrs, locale);
          scriptTags.add(scriptTag);
        }
      }
    }

    // don't allow the list to be modified by caller
    Collections.unmodifiableList(scriptTags);

    // cache the list for next time and return it
    if (cacheAssets && scriptTags.size() > 0) {
      scriptsCache.put(cacheKey, scriptTags);
    }

    return scriptTags;
  }

  /**
   * Returns a list of the meta tags for the id. If includeGlobal is true then
   * the global meta tags are also included.
   * 
   * @param id The unique id matching an id in a fooglue config file.
   * @param locale The current locale, used to resolve properties.
   * @param includeGlobal Include global meta tags.
   * 
   * @return The list of meta tags for the id and locale.
   */
  public List<String> getMetaTagsForId(String id, Locale locale,
    boolean includeGlobal) {

    // get the cache key from id and locale
    String cacheKey = getCacheKey(id, locale);

    // check the cache first
    if (metaCache.containsKey(cacheKey)) {
      return metaCache.get(cacheKey);
    }

    // not in cache create a new list and populate with the global scripts
    List<String> metaTags = new ArrayList<String>();

    // get the global and path assets
    Map globalAssets = (Map)assets.get(FooGlueConstants.GLOBAL);
    Map idAssets = (Map)assets.get(id);
    if (requireIdForAssets && idAssets == null) {
      return metaTags;
    }

    // add the global meta tags
    if (includeGlobal && globalAssets != null) {
      List<Map> globalMetas = (List<Map>)globalAssets
        .get(FooGlueConstants.METAS);
      if (globalMetas != null && globalMetas.size() > 0) {
        for (Map<String, String> metaAttrs : globalMetas) {
          String metaTag = createMetaTag(metaAttrs, locale);
          metaTags.add(metaTag);
        }
      }
    }

    // add the met tags for the path
    if (idAssets != null) {
      List<Map> idMetas = (List<Map>)idAssets.get(FooGlueConstants.METAS);
      if (idMetas != null && idMetas.size() > 0) {
        for (Map<String, String> metaAttrs : idMetas) {
          String metaTag = createMetaTag(metaAttrs, locale);
          metaTags.add(metaTag);
        }
      }
    }

    // don't allow the list to be modified by caller
    Collections.unmodifiableList(metaTags);

    // cache the list for next time and return it
    if (cacheAssets && metaTags.size() > 0) {
      metaCache.put(cacheKey, metaTags);
    }

    return metaTags;
  }

  /**
   * Returns a list of the link tags for the id. If includeGlobal is true then
   * the global link tags are also included.
   * 
   * @param id The unique id matching an id in a fooglue config file.
   * @param locale The current locale, used to resolve properties.
   * @param includeGlobal Include global link tags.
   * 
   * @return The list of link tags for the id and locale.
   */
  public List<String> getLinkTagsForId(String id, Locale locale,
    boolean includeGlobal) {

    // get the cache key from id and locale
    String cacheKey = getCacheKey(id, locale);

    // check the cache first
    if (linksCache.containsKey(cacheKey)) {
      return linksCache.get(cacheKey);
    }

    // not in cache create a new list and populate with the global scripts
    List<String> linkTags = new ArrayList<String>();

    // get the global and path assets
    Map globalAssets = (Map)assets.get(FooGlueConstants.GLOBAL);
    Map idAssets = (Map)assets.get(id);
    if (requireIdForAssets && idAssets == null) {
      return linkTags;
    }

    // add the global links
    if (includeGlobal && globalAssets != null) {
      List<Map> globalLinks = (List<Map>)globalAssets
        .get(FooGlueConstants.LINKS);
      if (globalLinks != null && globalLinks.size() > 0) {
        for (Map<String, String> linkAttrs : globalLinks) {
          String linkTag = createLinkTag(linkAttrs, locale);
          linkTags.add(linkTag);
        }
      }
    }

    // add the links for the path
    if (idAssets != null) {
      List<Map> idLinks = (List<Map>)idAssets.get(FooGlueConstants.LINKS);
      if (idLinks != null && idLinks.size() > 0) {
        for (Map<String, String> linkAttrs : idLinks) {
          String linkTag = createLinkTag(linkAttrs, locale);
          linkTags.add(linkTag);
        }
      }
    }

    // don't allow the list to be modified by caller
    Collections.unmodifiableList(linkTags);

    // cache the list for next time and return it
    if (cacheAssets && linkTags.size() > 0) {
      linksCache.put(cacheKey, linkTags);
    }

    return linkTags;
  }

  /**
   * Returns the title for the id. If includeGlobal is true and a title for the
   * id is not found then the global title is returned.
   * 
   * @param id The unique id matching an id in a fooglue config file.
   * @param locale The current locale, used to resolve properties.
   * @param includeGlobal Include global title if local not found.
   * 
   * @return The title for the id and locale.
   */
  public String getTitleTagForId(String id, Locale locale, boolean includeGlobal) {

    // get the cache key from id and locale
    String cacheKey = getCacheKey(id, locale);

    // check the cache first
    if (titleCache.containsKey(cacheKey)) {
      return titleCache.get(cacheKey);
    }

    // get the global and id assets
    Map globalAssets = (Map)assets.get(FooGlueConstants.GLOBAL);
    Map idAssets = (Map)assets.get(id);
    if (requireIdForAssets && idAssets == null) {
      return null;
    }

    // check global title if id title isn't present
    String title = null;
    if (idAssets != null) {
      title = (String)idAssets.get(FooGlueConstants.TITLE);
    }
    if (includeGlobal && globalAssets != null && StringUtils.isBlank(title)) {
      title = (String)globalAssets.get(FooGlueConstants.TITLE);
    }

    // convert to message if necessary
    if (StringUtils.isNotBlank(title)) {
      title = resolveProperty(title, locale);
    }

    // add title wrapper and cache
    if (StringUtils.isNotBlank(title)) {
      title = "<title>" + title + "</title>";
      if (cacheAssets) {
        titleCache.put(cacheKey, title);
      }
    }

    return title;
  }

  /**
   * Returns a list of dynamically created script tags for the locale. Dynamic
   * scripts are not cached.
   * 
   * @param scripts The script values, used to create the script tags.
   * @param locale The current locale, used to resolve properties.
   * 
   * @return A list of dynamically created script tags.
   */
  public List<String> getDynamicScriptTags(List scripts, Locale locale) {

    // create a new list every time for dynamic scripts
    List<String> scriptTags = new ArrayList<String>();

    // if we have scripts, loop through
    if (scripts != null && scripts.size() > 0) {
      for (int i = 0; i < scripts.size(); i++) {

        // either is a map of attributes or is just a string, create the single
        // script tag from either
        Object scriptObj = scripts.get(i);
        if (scriptObj instanceof Map) {
          Map<String, String> scriptAttrs = (Map<String, String>)scriptObj;
          if (scriptAttrs != null && scriptAttrs.size() > 0) {
            String scriptTag = createScriptTag(scriptAttrs, locale);
            scriptTags.add(scriptTag);
          }
        }
        else if (scriptObj instanceof String) {

          // set default values for script tag if shorthand string
          Map<String, String> scriptAttrs = new LinkedHashMap<String, String>();
          scriptAttrs.put("type", "text/javascript");
          scriptAttrs.put("src", (String)scriptObj);
          String scriptTag = createScriptTag(scriptAttrs, locale);
          scriptTags.add(scriptTag);
        }
      }
    }

    // don't allow the list to be modified by caller
    Collections.unmodifiableList(scriptTags);

    return scriptTags;
  }

  /**
   * Returns a list of dynamically created meta tags for the locale. Dynamic
   * meta tags are not cached.
   * 
   * @param metas The script values, used to create the meta tags.
   * @param locale The current locale, used to resolve properties.
   * 
   * @return A list of dynamically created meta tags.
   */
  public List<String> getDynamicMetaTags(List<Map<String, String>> metas,
    Locale locale) {

    // not in cache create a new list and populate with the global scripts
    List<String> metaTags = new ArrayList<String>();

    // if we have metas, loop through
    if (metas != null && metas.size() > 0) {
      for (Map<String, String> metaAttrs : metas) {
        if (metaAttrs != null && metaAttrs.size() > 0) {
          String metaTag = createMetaTag(metaAttrs, locale);
          metaTags.add(metaTag);
        }
      }
    }

    // don't allow the list to be modified by caller
    Collections.unmodifiableList(metaTags);

    return metaTags;
  }

  /**
   * Returns a list of dynamically created link tags for the locale. Dynamic
   * link tags are not cached.
   * 
   * @param links The script values, used to create the link tags.
   * @param locale The current locale, used to resolve properties.
   * 
   * @return A list of dynamically created link tags.
   */
  public List<String> getDynamicLinkTags(List links, Locale locale) {

    // create a new list every time for dynamic links
    List<String> linkTags = new ArrayList<String>();

    // if we have links, loop through
    if (links != null && links.size() > 0) {
      for (int i = 0; i < links.size(); i++) {

        // either is a map of attributes or is just a string, create the single
        // link tag from either
        Object linkObj = links.get(i);
        if (linkObj instanceof Map) {
          Map<String, String> linkAttrs = (Map<String, String>)linkObj;
          if (linkAttrs != null && linkAttrs.size() > 0) {
            String linkTag = createLinkTag(linkAttrs, locale);
            linkTags.add(linkTag);
          }
        }
        else if (linkObj instanceof String) {

          // set default values for link tag if shorthand string
          Map<String, String> linkAttrs = new LinkedHashMap<String, String>();
          linkAttrs.put("rel", "stylesheet");
          linkAttrs.put("type", "text/css");
          linkAttrs.put("href", (String)linkObj);
          String linkTag = createLinkTag(linkAttrs, locale);
          linkTags.add(linkTag);
        }
      }
    }

    // don't allow the list to be modified by caller
    Collections.unmodifiableList(linkTags);

    return linkTags;
  }

  /**
   * Returns a dynamically created title tags for the locale. Dynamic title tags
   * are not cached.
   * 
   * @param title The title value, used to create the title tag.
   * @param locale The current locale, used to resolve properties.
   * 
   * @return A dynamically created title tag.
   */
  public String getDynamicTitleTag(String title, Locale locale) {

    // convert to message if necessary
    if (StringUtils.isNotBlank(title)) {
      title = resolveProperty(title, locale);
      title = "<title>" + title + "</title>";
    }
    return title;
  }

  public void setConfigResources(Resource[] configResources) {

    // make sure global fooglue config is loaded first. this is needed for
    // global reloading
    for (int i = 0; i < configResources.length; i++) {
      try {

        // get the config file name, if it matches the global name, reorder
        // with the first entry
        String configName = configResources[i].getFile().getName();
        if (i > 0 && StringUtils.equalsIgnoreCase(configName, globalConfig)) {
          Resource global = configResources[i];
          configResources[i] = configResources[0];
          configResources[0] = global;
          break;
        }
      }
      catch (Exception e) {
        // ignore files that error, continue with other files
      }
    }

    this.configResources = configResources;
  }

  public boolean isCacheAssets() {
    return cacheAssets;
  }

  public void setCacheAssets(boolean cacheAssets) {
    this.cacheAssets = cacheAssets;
  }

  public boolean isMinifyAssets() {
    return minifyAssets;
  }

  public void setMinifyAssets(boolean minifyAssets) {
    this.minifyAssets = minifyAssets;
  }

  public String getRootDir() {
    return rootDir;
  }

  public void setRootDir(String rootDir) {
    this.rootDir = rootDir;
  }

  public String getAliasTagStart() {
    return aliasTagStart;
  }

  public void setAliasTagStart(String aliasTagStart) {
    this.aliasTagStart = aliasTagStart;
  }

  public String getAliasTagEnd() {
    return aliasTagEnd;
  }

  public void setAliasTagEnd(String aliasTagEnd) {
    this.aliasTagEnd = aliasTagEnd;
  }

  public String getPropertyTagStart() {
    return propertyTagStart;
  }

  public void setPropertyTagStart(String propertyTagStart) {
    this.propertyTagStart = propertyTagStart;
  }

  public String getPropertyTagEnd() {
    return propertyTagEnd;
  }

  public void setPropertyTagEnd(String propertyTagEnd) {
    this.propertyTagEnd = propertyTagEnd;
  }

  public long getReloadInterval() {
    return reloadInterval;
  }

  public void setReloadInterval(long reloadInterval) {
    this.reloadInterval = reloadInterval;
  }

  public String getCacheDir() {
    return cacheDir;
  }

  public void setCacheDir(String cacheDir) {
    this.cacheDir = cacheDir;
  }

  public String getAssetHost() {
    return assetHost;
  }

  public void setAssetHost(String assetHost) {
    this.assetHost = assetHost;
  }

  public MessageSource getMessageSource() {
    return messageSource;
  }

  public void setMessageSource(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  public boolean isRequireIdForAssets() {
    return requireIdForAssets;
  }

  public void setRequireIdForAssets(boolean requireIdForAssets) {
    this.requireIdForAssets = requireIdForAssets;
  }

}
