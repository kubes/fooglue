package com.igfoo.fooglue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface FooGlueService {

  // assets by path

  public List<String> getScriptTagsForId(String id, Locale locale,
    boolean includeGlobal);

  public List<String> getMetaTagsForId(String id, Locale locale,
    boolean includeGlobal);

  public List<String> getLinkTagsForId(String id, Locale locale,
    boolean includeGlobal);

  public String getTitleTagForId(String id, Locale locale, boolean includeGlobal);

  // dynamic assets

  public List<String> getDynamicScriptTags(List scripts, Locale locale);

  public List<String> getDynamicMetaTags(List<Map<String, String>> metas,
    Locale locale);

  public List<String> getDynamicLinkTags(List links, Locale locale);

  public String getDynamicTitleTag(String title, Locale locale);
}
