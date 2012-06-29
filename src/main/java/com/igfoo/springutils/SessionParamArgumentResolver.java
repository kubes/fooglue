package com.igfoo.springutils;

import java.lang.annotation.Annotation;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.core.MethodParameter;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.support.WebArgumentResolver;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * An ArgumentResolver that allows session scoped objects to be bound as 
 * parameters into method calls.
 */
public class SessionParamArgumentResolver
  implements WebArgumentResolver {

  public Object resolveArgument(MethodParameter param, NativeWebRequest request)
    throws Exception {

    Annotation[] paramAnns = param.getParameterAnnotations();
    Class paramType = param.getParameterType();

    for (Annotation paramAnn : paramAnns) {

      if (SessionParam.class.isInstance(paramAnn)) {

        SessionParam sessionParam = (SessionParam)paramAnn;
        String paramName = sessionParam.value();
        boolean required = sessionParam.required();
        String defaultValue = sessionParam.defaultValue();

        HttpServletRequest req = (HttpServletRequest)request.getNativeRequest();
        HttpSession session = req.getSession(false);

        Object result = null;
        if (session != null) {
          result = session.getAttribute(paramName);
        }
        if (result == null) {
          result = defaultValue;
        }
        if (result == null && required && session == null) {
          raiseSessionRequiredException(paramName, paramType);
        }
        if (result == null && required) {
          raiseMissingParameterException(paramName, paramType);
        }

        return result;
      }
    }

    return WebArgumentResolver.UNRESOLVED;

  }

  protected void raiseMissingParameterException(String paramName,
    Class paramType)
    throws Exception {
    throw new IllegalStateException("Missing parameter '" + paramName
      + "' of type [" + paramType.getName() + "]");
  }

  protected void raiseSessionRequiredException(String paramName, Class paramType)
    throws Exception {
    throw new HttpSessionRequiredException(
      "No HttpSession found for resolving parameter '" + paramName
        + "' of type [" + paramType.getName() + "]");
  }
}
