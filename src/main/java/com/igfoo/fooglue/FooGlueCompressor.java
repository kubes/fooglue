package com.igfoo.fooglue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;

import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.platform.yui.compressor.CssCompressor;
import com.yahoo.platform.yui.compressor.JavaScriptCompressor;

public class FooGlueCompressor {

  private final static Logger LOG = LoggerFactory
    .getLogger(FooGlueCompressor.class);

  private static class AssetCompressorErrorReporter
    implements ErrorReporter {

    public void warning(String message, String sourceName, int line,
      String lineSource, int lineOffset) {
      if (line < 0) {
        LOG.warn(message);
      }
      else {
        LOG.warn(line + ':' + lineOffset + ':' + message);
      }
    }

    public void error(String message, String sourceName, int line,
      String lineSource, int lineOffset) {
      if (line < 0) {
        LOG.error(message);
      }
      else {
        LOG.error(line + ':' + lineOffset + ':' + message);
      }
    }

    public EvaluatorException runtimeError(String message, String sourceName,
      int line, String lineSource, int lineOffset) {
      error(message, sourceName, line, lineSource, lineOffset);
      return new EvaluatorException(message);
    }
  }

  public static class Options {
    public String charset = "UTF-8";
    public int lineBreakPos = -1;
    public boolean munge = true;
    public boolean verbose = false;
    public boolean preserveAllSemiColons = false;
    public boolean disableOptimizations = false;
  }

  public static void compressJavaScript(InputStream is, OutputStream os,
    Options options)
    throws IOException {

    Reader reader = new InputStreamReader(is, options.charset);
    JavaScriptCompressor compressor = new JavaScriptCompressor(reader,
      new AssetCompressorErrorReporter());
    reader.close();

    Writer writer = new OutputStreamWriter(os, options.charset);
    compressor.compress(writer, options.lineBreakPos, options.munge,
      options.verbose, options.preserveAllSemiColons,
      options.disableOptimizations);
    writer.flush();
    writer.close();
  }

  public static void compressJavaScript(InputStream is, OutputStream os)
    throws IOException {
    compressJavaScript(is, os, new Options());
  }

  public static void compressStyleSheet(InputStream is, OutputStream os,
    Options options)
    throws IOException {

    Reader reader = new InputStreamReader(is, options.charset);
    CssCompressor compressor = new CssCompressor(reader);
    reader.close();

    Writer writer = new OutputStreamWriter(os, options.charset);
    compressor.compress(writer, options.lineBreakPos);
    writer.flush();
    writer.close();
  }

  public static void compressStyleSheet(InputStream is, OutputStream os)
    throws IOException {
    compressStyleSheet(is, os, new Options());
  }
}
