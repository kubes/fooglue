package com.igfoo.fooglue;

import java.util.List;
import java.util.Locale;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
  "/fooglue/testing-context.xml"
})
public class TestFooGlueService {

  @Autowired
  private FooGlueService fooglue;

  // script tags
  private String script1 = "<script type=\"text/javascript\" src=\"http://localhost/js/one.js\"></script>";
  private String script2 = "<script type=\"text/javascript\" src=\"http://localhost/js/two.js\"></script>";
  private String script3 = "<script type=\"text/javascript\" src=\"http://localhost/js/three.js\"></script>";
  private String script4 = "<script type=\"text/javascript\" src=\"http://localhost/js/testing1.js\"></script>";

  // stylesheet tags
  private String link1 = "<link rel=\"stylesheet\" type=\"text/css\" href=\"http://localhost/css/one.css\" />";
  private String link2 = "<link rel=\"stylesheet\" type=\"text/css\" href=\"http://localhost/css/two.css\" />";
  private String link3 = "<link rel=\"stylesheet\" type=\"text/css\" href=\"http://localhost/css/three.css\" />";
  private String link4 = "<link rel=\"stylesheet\" type=\"text/css\" href=\"http://localhost/css/testing1.css\" />";

  // meta tags
  private String meta1 = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />";
  private String meta2 = "<meta name=\"keywords\" content=\"one two\" />";

  // title tags
  private String title1 = "<title>default title</title>";
  private String title2 = "<title>testing1 title</title>";

  @Test
  public void testGlobalScriptTags() {

    // get global scripts only
    List<String> scriptTags = fooglue.getScriptTagsForId("0", Locale.US, true);
    Assert.assertTrue(scriptTags.size() == 3);

    // validate script tag creation and alias resolution
    Assert.assertEquals(scriptTags.get(0), script1);
    Assert.assertEquals(scriptTags.get(1), script2);
    Assert.assertEquals(scriptTags.get(2), script3);
  }

  @Test
  public void testGlobalLinkTags() {

    // get global link tags only
    List<String> linkTags = fooglue.getLinkTagsForId("0", Locale.US, true);
    Assert.assertTrue(linkTags.size() == 3);

    // validate link tag creation and alias resolution
    Assert.assertEquals(linkTags.get(0), link1);
    Assert.assertEquals(linkTags.get(1), link2);
    Assert.assertEquals(linkTags.get(2), link3);
  }

  @Test
  public void testGlobalMetaTags() {

    // get global meta tags only
    List<String> metaTags = fooglue.getMetaTagsForId("0", Locale.US, true);
    Assert.assertTrue(metaTags.size() == 1);

    // validate meta tag creation and alias resolution
    Assert.assertEquals(metaTags.get(0), meta1);
  }

  @Test
  public void testGlobalTitle() {

    // get global title tag only
    String titleTag = fooglue.getTitleTagForId("0", Locale.US, true);

    // validate title tag creation and property resolution
    Assert.assertEquals(titleTag, title1);
  }

  @Test
  public void testCombinedScriptTags() {

    // get combined global and id specific script tags
    List<String> scriptTags = fooglue.getScriptTagsForId("testing1", Locale.US,
      true);
    Assert.assertTrue(scriptTags.size() == 4);

    // validate script tag creation and alias resolution and script ordering
    Assert.assertEquals(scriptTags.get(0), script1);
    Assert.assertEquals(scriptTags.get(1), script2);
    Assert.assertEquals(scriptTags.get(2), script3);
    Assert.assertEquals(scriptTags.get(3), script4);
  }

  @Test
  public void testCombinedLinkTags() {

    // get combined global and id specific link tags
    List<String> linkTags = fooglue.getLinkTagsForId("testing1", Locale.US,
      true);
    Assert.assertTrue(linkTags.size() == 4);

    // validate link tag creation and alias resolution and link ordering
    Assert.assertEquals(linkTags.get(0), link1);
    Assert.assertEquals(linkTags.get(1), link2);
    Assert.assertEquals(linkTags.get(2), link3);
    Assert.assertEquals(linkTags.get(3), link4);
  }

  @Test
  public void testCombinedMetaTags() {

    // get combined global and id specific meta tags
    List<String> metaTags = fooglue.getMetaTagsForId("testing1", Locale.US,
      true);
    Assert.assertTrue(metaTags.size() == 2);

    // validates meta tag creation and meta tag ordering
    Assert.assertEquals(metaTags.get(0), meta1);
    Assert.assertEquals(metaTags.get(1), meta2);
  }

  @Test
  public void testCombinedTitle() {

    // get combined title tag, which should give only the id specific title
    String titleTag = fooglue.getTitleTagForId("testing1", Locale.US, true);
    
    // validate id specific title tag is used, global is ignored
    Assert.assertEquals(titleTag, title2);
  }

}
