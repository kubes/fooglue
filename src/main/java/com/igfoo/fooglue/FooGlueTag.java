package com.igfoo.fooglue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.tagext.TagSupport;

import org.apache.commons.lang.StringUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * <p>JSTL Tag that writes out fooglue assets including script tags, link tags,
 * meta tags, and titles.</p>
 * 
 * <p>Options can be set to include global assets and to include dynamic assets.
 * Global assets are configured in the global fooglue config file. Dynamic
 * assets are setup in the request by the Spring controller.</p>
 * 
 * <p>There are four different asset type that can be written. The are script,
 * meta, link, and title. Script writes out script tags, usually javascript.
 * Meta writes out meta tags. Link writes out link tags, usually stylesheets.
 * And title writes out the page title.</p>
 * 
 * <p>If ids are specified on the tag they override any ids setup in the Spring
 * controller. Usually one or more ids are specified by the controller and put
 * into the request. Ids are only specified on the tag in special cases, such as
 * when you have specific scripts that run in a specific location on a page. An
 * example of this would be analytics or advertisements.</p>
 */
public class FooGlueTag
  extends TagSupport {

  private String types;
  private String ids;
  private boolean includeGlobal = false;
  private boolean includeDynamic = false;

  public void setTypes(String types) {
    this.types = types;
  }

  public void setIds(String ids) {
    this.ids = ids;
  }

  public void setIncludeGlobal(boolean includeGlobal) {
    this.includeGlobal = includeGlobal;
  }

  public void setIncludeDynamic(boolean includeDynamic) {
    this.includeDynamic = includeDynamic;
  }

  public int doStartTag()
    throws JspException {

    try {

      // get the fooglue service from the Spring web application context
      HttpServletRequest request = (HttpServletRequest)pageContext.getRequest();
      WebApplicationContext context = RequestContextUtils
        .getWebApplicationContext(request);
      FooGlueService fg = (FooGlueService)context.getBean("fooGlueService");

      // get the current locale and the output writer
      Locale curLocale = request.getLocale();
      JspWriter out = pageContext.getOut();

      if (request != null) {

        // get the types of assets for this tag
        Set<String> typeSet = new HashSet<String>();
        boolean allTypes = StringUtils.isBlank(types);
        if (!allTypes) {
          String[] includeTypesAr = StringUtils.split(types, ",");
          for (String includeType : includeTypesAr) {
            includeType = StringUtils.trim(StringUtils.lowerCase(includeType));
            typeSet.add(includeType);
          }
        }

        // are ids hardcoded on the tag itself, overrides anything specified
        // in the request. an id must be specified either on the tag or in the
        // request, even though they don't have to exist in the configuration
        boolean tagSpecifiedIds = (this.ids != null);
        String idStr = (tagSpecifiedIds) ? this.ids : (String)request
          .getAttribute(FooGlueConstants.IDS);

        // dedup tag ids, keep in order
        Set<String> tagIdSet = new LinkedHashSet<String>();
        for (String tagId : StringUtils.split(idStr, ",")) {
          tagId = StringUtils.trim(tagId);
          tagIdSet.add(tagId);
        }

        // process the title
        if (allTypes || types.contains("title")) {

          String title = null;

          // use only the first title found for an id, can't have multiple
          for (String tagId : tagIdSet) {
            title = fg.getTitleTagForId(tagId, curLocale, includeGlobal);
            if (StringUtils.isNotBlank(title)) {
              break;
            }
          }

          // dynamic titles from controller will override any title for id if
          // we are including dynamic titles
          if (includeDynamic) {
            String dynTitle = (String)request
              .getAttribute(FooGlueConstants.TITLE);
            if (StringUtils.isNotBlank(dynTitle)) {
              String locTitle = fg.getDynamicTitleTag(dynTitle, curLocale);
              if (StringUtils.isNotBlank(locTitle)) {
                title = locTitle;
              }
            }
          }

          // write out the title
          if (StringUtils.isNotBlank(title)) {
            out.print(title + "\n");
            request.setAttribute(FooGlueConstants.TITLE_TAG, title);
          }
        }

        // process the meta tags
        if (allTypes || types.contains("meta")) {

          // get all meta tags for the ids first
          List<String> allMetaTags = new ArrayList<String>();
          for (String tagId : tagIdSet) {
            List<String> metaTags = fg.getMetaTagsForId(tagId, curLocale,
              includeGlobal);
            if (metaTags != null && metaTags.size() > 0) {
              allMetaTags.addAll(metaTags);
            }
          }

          // include dynamic meta tags if specified and including
          if (includeDynamic) {
            List<Map<String, String>> requestMetaTags = (List<Map<String, String>>)request
              .getAttribute(FooGlueConstants.METAS);
            if (requestMetaTags != null && requestMetaTags.size() > 0) {
              List<String> dynamicMetaTags = fg.getDynamicMetaTags(
                requestMetaTags, curLocale);
              if (allMetaTags != null && dynamicMetaTags.size() > 0) {
                allMetaTags.addAll(dynamicMetaTags);
              }
            }
          }

          // write out the meta tags and put into request
          if (allMetaTags != null && !allMetaTags.isEmpty()) {
            for (String metaTag : allMetaTags) {
              out.print(metaTag + "\n");
            }
            request.setAttribute(FooGlueConstants.META_TAGS, allMetaTags);
          }
        }

        // process the link tags
        if (allTypes || types.contains("link")) {

          // get all link tags for the ids first
          List<String> allLinkTags = new ArrayList<String>();
          for (String tagId : tagIdSet) {
            List<String> linkTags = fg.getLinkTagsForId(tagId, curLocale,
              includeGlobal);
            if (linkTags != null && linkTags.size() > 0) {
              allLinkTags.addAll(linkTags);
            }
          }

          // include dynamic link tags if specified and including
          if (includeDynamic) {
            List<Map<String, String>> requestLinkTags = (List<Map<String, String>>)request
              .getAttribute(FooGlueConstants.LINKS);
            if (requestLinkTags != null && requestLinkTags.size() > 0) {
              List<String> dynamicLinkTags = fg.getDynamicLinkTags(
                requestLinkTags, curLocale);
              if (dynamicLinkTags != null && dynamicLinkTags.size() > 0) {
                allLinkTags.addAll(dynamicLinkTags);
              }
            }
          }

          // write out the link tags and put into request
          if (allLinkTags != null && allLinkTags.size() > 0) {
            for (String linkTag : allLinkTags) {
              out.print(linkTag + "\n");
            }
            request.setAttribute(FooGlueConstants.LINK_TAGS, allLinkTags);
          }
        }

        // process script tags
        if (allTypes || types.contains("script")) {

          // get all link tags for the ids first
          List<String> allScriptTags = new ArrayList<String>();
          for (String tagId : tagIdSet) {
            List<String> scriptTags = fg.getScriptTagsForId(tagId,
              curLocale, includeGlobal);
            if (scriptTags != null && scriptTags.size() > 0) {
              allScriptTags.addAll(scriptTags);
            }
          }

          // include dynamic link tags if specified and including
          if (includeDynamic) {
            List<Map<String, String>> requestScripts = (List<Map<String, String>>)request
              .getAttribute(FooGlueConstants.SCRIPTS);
            if (requestScripts != null && requestScripts.size() > 0) {
              List<String> dynamicScripts = fg.getDynamicScriptTags(
                requestScripts, curLocale);
              if (dynamicScripts != null && dynamicScripts.size() > 0) {
                allScriptTags.addAll(dynamicScripts);
              }
            }
          }

          // write out the script tags and put into request
          if (allScriptTags != null && allScriptTags.size() > 0) {
            for (String scriptTag : allScriptTags) {
              out.print(scriptTag + "\n");
            }
            request.setAttribute(FooGlueConstants.SCRIPT_TAGS, allScriptTags);
          }
        }
      }

    }
    catch (IOException e) {
      throw new JspException(e);
    }

    return SKIP_BODY;
  }

  public int doEndTag() {
    return EVAL_PAGE;
  }
}
