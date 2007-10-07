/*
 * Copyright 2006 Web Cohesion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.enunciate.contract.jaxws;

import com.sun.mirror.declaration.ClassDeclaration;
import com.sun.mirror.declaration.MethodDeclaration;
import com.sun.mirror.declaration.ParameterDeclaration;
import com.sun.mirror.declaration.TypeDeclaration;
import com.sun.mirror.type.DeclaredType;
import com.sun.mirror.type.ReferenceType;
import com.sun.mirror.type.VoidType;
import com.sun.mirror.type.TypeMirror;
import net.sf.jelly.apt.decorations.declaration.DecoratedMethodDeclaration;
import org.codehaus.enunciate.contract.validation.ValidationException;
import org.codehaus.enunciate.util.MapTypeUtil;
import org.codehaus.enunciate.util.MapType;

import javax.jws.Oneway;
import javax.jws.soap.SOAPBinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A method invoked on a web service.
 *
 * @author Ryan Heaton
 */
public class WebMethod extends DecoratedMethodDeclaration implements Comparable<WebMethod> {

  private final javax.jws.WebMethod annotation;
  private final boolean oneWay;
  private final EndpointInterface endpointInterface;
  private final WebResult webResult;
  private final Collection<WebParam> webParams;
  private final Collection<WebFault> webFaults;
  private final Collection<WebMessage> messages;

  public WebMethod(MethodDeclaration delegate, EndpointInterface endpointInterface) {
    super(delegate);

    this.annotation = getAnnotation(javax.jws.WebMethod.class);
    this.oneWay = getAnnotation(Oneway.class) != null;
    this.endpointInterface = endpointInterface;
    TypeMirror returnType = getReturnType();
    MapType mapType = MapTypeUtil.findMapType(returnType);
    if (mapType != null) {
      returnType = mapType;
    }
    this.webResult = new WebResult(returnType, this);

    Collection<ParameterDeclaration> parameters = getParameters();
    Collection<WebParam> webParameters = new ArrayList<WebParam>(parameters.size());
    for (ParameterDeclaration parameter : parameters) {
      webParameters.add(new WebParam(parameter, this));
    }
    this.webParams = webParameters;

    Collection<WebFault> webFaults = new ArrayList<WebFault>();
    for (ReferenceType referenceType : getThrownTypes()) {
      if (!(referenceType instanceof DeclaredType)) {
        throw new ValidationException(getPosition(), "Thrown type must be a declared type.");
      }

      TypeDeclaration declaration = ((DeclaredType) referenceType).getDeclaration();

      if (declaration == null) {
        throw new ValidationException(getPosition(), "Unknown declaration for " + referenceType);
      }

      webFaults.add(new WebFault((ClassDeclaration) declaration));
    }
    this.webFaults = webFaults;

    Collection<WebMessage> messages = new ArrayList<WebMessage>();
    SOAPBinding.Style bindingStyle = getSoapBindingStyle();

    //first add all the headers.
    for (WebParam webParam : webParameters) {
      if (webParam.isHeader()) {
        messages.add(webParam);
      }
    }

    if (webResult.isHeader()) {
      messages.add(webResult);
    }

    if (bindingStyle == SOAPBinding.Style.DOCUMENT) {
      SOAPBinding.ParameterStyle parameterStyle = getSoapParameterStyle();
      if (parameterStyle == SOAPBinding.ParameterStyle.WRAPPED) {
        messages.add(new RequestWrapper(this));
        if (!isOneWay()) {
          messages.add(new ResponseWrapper(this));
        }
        messages.addAll(webFaults);
      }
      else {
        for (WebParam webParam : webParameters) {
          if (!webParam.isHeader()) {
            messages.add(webParam);
          }
        }
        if ((!isOneWay()) && (!(getReturnType() instanceof VoidType))) {
          messages.add(webResult);
        }

        messages.addAll(webFaults);
      }
    }
    else {
      messages.add(new RPCInputMessage(this));
      messages.add(new RPCOutputMessage(this));
      messages.addAll(webFaults);
    }

    this.messages = messages;
  }


  @Override
  public TypeMirror getReturnType() {
    TypeMirror type = super.getReturnType();
    MapType mapType = MapTypeUtil.findMapType(type);
    if (mapType != null) {
      type = mapType;
    }
    return type;
  }

  /**
   * The web result of this web method.
   *
   * @return The web result of this web method.
   */
  public WebResult getWebResult() {
    return this.webResult;
  }

  /**
   * The list of web parameters for this method.
   *
   * @return The list of web parameters for this method.
   */
  public Collection<WebParam> getWebParameters() {
    return this.webParams;
  }

  /**
   * The list of web faults thrown by this method.
   *
   * @return The list of web faults thrown by this method.
   */
  public Collection<WebFault> getWebFaults() {
    return this.webFaults;
  }

  /**
   * The messages of this web method.
   *
   * @return The messages of this web method.
   */
  public Collection<WebMessage> getMessages() {
    return this.messages;
  }

  /**
   * A set of the reference namespace for this method.
   *
   * @return A set of the reference namespace for this method.
   */
  public Set<String> getReferencedNamespaces() {
    HashSet<String> namespaces = new HashSet<String>();

    Collection<WebMessage> messages = getMessages();
    for (WebMessage message : messages) {
      for (WebMessagePart part : message.getParts()) {
        namespaces.add(part.getParticleQName().getNamespaceURI());
      }
    }
    
    return namespaces;
  }

  /**
   * The operation name of this web method.
   *
   * @return The operation name of this web method.
   */
  public String getOperationName() {
    String operationName = getSimpleName();

    if ((annotation != null) && (!"".equals(annotation.operationName()))) {
      return annotation.operationName();
    }

    return operationName;
  }

  /**
   * The action of this web method.
   *
   * @return The action of this web method.
   */
  public String getAction() {
    String action = "";

    if (annotation != null) {
      action = annotation.action();
    }

    return action;
  }

  /**
   * Whether this web method is one-way.
   *
   * @return Whether this web method is one-way.
   */
  public boolean isOneWay() {
    return oneWay;
  }

  /**
   * The SOAP binding style of this web method.
   *
   * @return The SOAP binding style of this web method.
   */
  public SOAPBinding.Style getSoapBindingStyle() {
    SOAPBinding.Style style = getDeclaringEndpointInterface().getSoapBindingStyle();
    SOAPBinding bindingInfo = getAnnotation(SOAPBinding.class);

    if (bindingInfo != null) {
      style = bindingInfo.style();
    }

    return style;
  }

  /**
   * The declaring web service for this web method.
   *
   * @return The declaring web service for this web method.
   */
  public EndpointInterface getDeclaringEndpointInterface() {
    return endpointInterface;
  }

  /**
   * The SOAP binding use of this web method.
   *
   * @return The SOAP binding use of this web method.
   */
  public SOAPBinding.Use getSoapUse() {
    SOAPBinding.Use use = getDeclaringEndpointInterface().getSoapUse();
    SOAPBinding bindingInfo = getAnnotation(SOAPBinding.class);

    if (bindingInfo != null) {
      use = bindingInfo.use();
    }

    return use;
  }

  /**
   * The SOAP parameter style of this web method.
   *
   * @return The SOAP parameter style of this web method.
   */
  public SOAPBinding.ParameterStyle getSoapParameterStyle() {
    SOAPBinding.ParameterStyle style = getDeclaringEndpointInterface().getSoapParameterStyle();
    SOAPBinding bindingInfo = getAnnotation(SOAPBinding.class);

    if (bindingInfo != null) {
      style = bindingInfo.parameterStyle();
    }

    return style;
  }

  /**
   * Determine whether this web method is doc/lit wrapped.
   *
   * @return Whether this web method is doc/lit wrapped.
   */
  public boolean isDocLitWrapped() {
    return getSoapBindingStyle() == SOAPBinding.Style.DOCUMENT &&
      getSoapUse() == SOAPBinding.Use.LITERAL &&
      getSoapParameterStyle() == SOAPBinding.ParameterStyle.WRAPPED;
  }

  /**
   * Web methods must be unique by name.  (JSR 181: 3.1.1)
   *
   * @param webMethod The web method to compare this to.
   * @return The comparison.
   */
  public int compareTo(WebMethod webMethod) {
    return getOperationName().compareTo(webMethod.getOperationName());
  }

}