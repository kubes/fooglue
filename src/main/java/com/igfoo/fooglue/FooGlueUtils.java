package com.igfoo.fooglue;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;

/**
 * Utility methods for passing request configuration from the Spring controller
 * to the JSTL FooGlueTag for displaying assets.
 */
public class FooGlueUtils {

  /**
   * Adds the fooglue id to the request. This id is used by the JSTL FooGlueTag
   * to display assets on JSP pages.
   * 
   * @param request The current HttpServletRequest.
   * @param id The fooglue asset configuration id.
   */
  public static void setupRequest(HttpServletRequest request, String id) {
    request.setAttribute(FooGlueConstants.IDS, id);
  }

  /**
   * Adds the fooglue ids to the request. These ids are used by the JSTL
   * FooGlueTag to display assets on JSP pages.
   * 
   * @param request The current HttpServletRequest.
   * @param ids The fooglue asset configuration ids.
   */
  public static void setupRequest(HttpServletRequest request, String[] ids) {
    String idStr = StringUtils.join(ids, ",");
    request.setAttribute(FooGlueConstants.IDS, idStr);
  }
}
