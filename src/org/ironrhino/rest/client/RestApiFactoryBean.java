package org.ironrhino.rest.client;

import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.util.ReflectionUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

import lombok.Getter;
import lombok.Setter;

public class RestApiFactoryBean implements MethodInterceptor, FactoryBean<Object> {

	private static final boolean resilience4jPresent = ClassUtils.isPresent(
			"io.github.resilience4j.circuitbreaker.CircuitBreaker", RestApiFactoryBean.class.getClassLoader());

	private final Class<?> restApiClass;

	@Autowired
	private ApplicationContext ctx;

	@Getter
	@Setter
	private RestTemplate restTemplate;

	@Getter
	@Setter
	private String apiBaseUrl;

	private Object restApiBean;

	@Value("${restApi.circuitBreakerEnabled:true}")
	private boolean circuitBreakerEnabled = true;

	@Getter
	private Object circuitBreaker;

	public RestApiFactoryBean(Class<?> restApiClass) {
		this(restApiClass, null);
	}

	public RestApiFactoryBean(Class<?> restApiClass, RestTemplate restTemplate) {
		Assert.notNull(restApiClass, "restApiClass shouldn't be null");
		if (restTemplate == null) {
			restTemplate = new org.ironrhino.core.spring.http.client.RestTemplate();
			Iterator<HttpMessageConverter<?>> it = restTemplate.getMessageConverters().iterator();
			while (it.hasNext()) {
				if (it.next() instanceof MappingJackson2XmlHttpMessageConverter)
					it.remove();
			}
		}
		if (!restApiClass.isInterface())
			throw new IllegalArgumentException(restApiClass.getName() + " should be interface");
		this.restApiClass = restApiClass;
		this.restTemplate = restTemplate;
		this.restApiBean = new ProxyFactory(restApiClass, this).getProxy(restApiClass.getClassLoader());
		if (circuitBreakerEnabled && resilience4jPresent) {
			io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
					.custom().recordFailure(ex -> ex.getCause() instanceof IOException).build();
			circuitBreaker = io.github.resilience4j.circuitbreaker.CircuitBreaker.of(restApiClass.getName(), config);
		}
	}

	public void setRestClient(RestClient restClient) {
		this.restTemplate = restClient.getRestTemplate();
	}

	@Override
	public Object getObject() throws Exception {
		return restApiBean;
	}

	@Override
	@NonNull
	public Class<?> getObjectType() {
		return restApiClass;
	}

	@Override
	public Object invoke(final MethodInvocation methodInvocation) throws Exception {
		if (circuitBreaker == null)
			return doInvoke(methodInvocation);
		else
			return ((io.github.resilience4j.circuitbreaker.CircuitBreaker) circuitBreaker)
					.executeCallable(() -> doInvoke(methodInvocation));
	}

	@SuppressWarnings("unchecked")
	public Object doInvoke(MethodInvocation methodInvocation) throws IOException {
		if (AopUtils.isToStringMethod(methodInvocation.getMethod()))
			return "RestApi for  [" + getObjectType().getName() + "]";
		Method method = methodInvocation.getMethod();
		RequestMapping classRequestMapping = AnnotatedElementUtils.findMergedAnnotation(restApiClass,
				RequestMapping.class);
		RequestMapping methodRequestMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
		if (classRequestMapping == null && methodRequestMapping == null)
			throw new UnsupportedOperationException("@RequestMapping should be present");
		StringBuilder sb = new StringBuilder(apiBaseUrl);
		if (classRequestMapping != null && classRequestMapping.value().length > 0)
			sb.append(classRequestMapping.value()[0]);
		if (methodRequestMapping != null && methodRequestMapping.value().length > 0)
			sb.append(methodRequestMapping.value()[0]);
		String url = ctx.getEnvironment().resolvePlaceholders(sb.toString().trim());
		RequestMethod[] requestMethods = methodRequestMapping != null ? methodRequestMapping.method()
				: classRequestMapping.method();
		RequestMethod requestMethod = requestMethods.length > 0 ? requestMethods[0] : RequestMethod.GET;

		Map<String, Object> pathVariables = new HashMap<>(8);
		MultiValueMap<String, String> headers = new HttpHeaders();
		MultiValueMap<String, Object> requestParams = null;
		Map<String, String> cookieValues = null;
		List<Object> requestParamsObjectCandidates = new ArrayList<>(1);
		Object body = null;
		String[] parameterNames = ReflectionUtils.getParameterNames(method);
		Object[] arguments = methodInvocation.getArguments();
		Annotation[][] array = method.getParameterAnnotations();
		InputStream is = null;
		boolean multipart = false;
		for (int i = 0; i < array.length; i++) {
			Object argument = arguments[i];
			if (argument == null)
				continue;
			if (argument instanceof InputStream)
				is = (InputStream) argument;
			boolean annotationPresent = false;
			for (Annotation anno : array[i]) {
				if (anno instanceof PathVariable) {
					annotationPresent = true;
					String name = ((PathVariable) anno).value();
					if (StringUtils.isBlank(name))
						name = parameterNames[i];
					pathVariables.put(name, argument);
				}
				if (anno instanceof RequestHeader) {
					annotationPresent = true;
					String name = ((RequestHeader) anno).value();
					if (StringUtils.isBlank(name))
						name = parameterNames[i];
					if (argument instanceof Collection) {
						for (Object o : (Collection<Object>) argument)
							if (o != null)
								headers.add(name, o.toString());
					} else {
						headers.add(name, argument.toString());
					}
				}
				if (anno instanceof RequestParam) {
					annotationPresent = true;
					String name = ((RequestParam) anno).value();
					if (StringUtils.isBlank(name))
						name = parameterNames[i];
					if (requestParams == null)
						requestParams = new LinkedMultiValueMap<>(8);
					if (argument instanceof Collection) {
						for (Object o : (Collection<Object>) argument)
							if (o != null) {
								if (o instanceof File)
									o = new FileSystemResource((File) o);
								else if (o instanceof InputStream)
									o = new InputStreamResource((InputStream) o);
								if (o instanceof Resource)
									multipart = true;
								else
									o = o.toString();
								requestParams.add(name, o);
							}
					} else {
						Object o = argument;
						if (o instanceof File)
							o = new FileSystemResource((File) o);
						else if (o instanceof InputStream)
							o = new InputStreamResource((InputStream) o);
						if (o instanceof Resource)
							multipart = true;
						else
							o = o.toString();
						requestParams.add(name, o);
					}
				}
				if (anno instanceof CookieValue) {
					annotationPresent = true;
					String name = ((CookieValue) anno).value();
					if (StringUtils.isBlank(name))
						name = parameterNames[i];
					if (cookieValues == null)
						cookieValues = new HashMap<>(8);
					cookieValues.put(name, argument.toString());
				}
				if (anno instanceof RequestBody) {
					annotationPresent = true;
					body = argument;
				}
			}
			// Put arguments to pathVariable even if @PathVariable not present
			if (!annotationPresent) {
				pathVariables.put(parameterNames[i], argument);
				String clazz = argument.getClass().getName();
				if (!argument.getClass().isEnum() && !clazz.startsWith("java.") && !clazz.startsWith("javax.")) {
					requestParamsObjectCandidates.add(argument);
				}
			}
		}
		if (requestParamsObjectCandidates.size() == 1 && requestParams == null) {
			requestParams = new LinkedMultiValueMap<>(8);
			BeanWrapperImpl bw = new BeanWrapperImpl(requestParamsObjectCandidates.get(0));
			for (PropertyDescriptor pd : bw.getPropertyDescriptors()) {
				if (pd.getReadMethod() == null || pd.getWriteMethod() == null)
					continue;
				String name = pd.getName();
				Object argument = bw.getPropertyValue(name);
				if (argument == null)
					continue;
				if (requestParams == null)
					requestParams = new LinkedMultiValueMap<>(8);
				if (argument instanceof Collection) {
					for (Object o : (Collection<Object>) argument)
						if (o != null)
							requestParams.add(name, o.toString());
				} else {
					requestParams.add(name, argument.toString());
				}
			}
		}

		while (url.contains("{")) {
			int index = url.indexOf('{');
			String prefix = url.substring(0, index);
			if (!url.endsWith("}"))
				prefix = null;
			url = URLDecoder.decode(restTemplate.getUriTemplateHandler().expand(url, pathVariables).toString(),
					"UTF-8");
			if (url.indexOf("http://") > 0)
				url = url.substring(url.indexOf("http://"));
			else if (url.indexOf("https://") > 0)
				url = url.substring(url.indexOf("https://"));
			else if (prefix != null && prefix.length() > 0 && url.substring(index).startsWith(prefix))
				url = url.substring(index);
		}
		if (cookieValues != null) {
			StringBuilder cookie = new StringBuilder();
			for (Map.Entry<String, String> entry : cookieValues.entrySet())
				cookie.append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), "UTF-8"))
						.append("; ");
			cookie.delete(cookie.length() - 2, cookie.length());
			headers.set(HttpHeaders.COOKIE, cookie.toString());
		}
		if (requestMethod.name().startsWith("P")) {
			if (body == null && is != null)
				body = is;
			if (body instanceof InputStream)
				body = new InputStreamResource((InputStream) body);
		}
		if (requestParams != null) {
			if (body == null && requestMethod.name().startsWith("P")) {
				if (!headers.containsKey(HttpHeaders.CONTENT_TYPE))
					headers.set(HttpHeaders.CONTENT_TYPE, multipart ? MediaType.MULTIPART_FORM_DATA_VALUE
							: MediaType.APPLICATION_FORM_URLENCODED_VALUE);
				body = requestParams;
			} else {
				StringBuilder temp = new StringBuilder(url);
				for (Map.Entry<String, List<Object>> entry : requestParams.entrySet())
					for (Object value : entry.getValue())
						if (value instanceof String)
							temp.append(temp.indexOf("?") < 0 ? '?' : '&').append(entry.getKey()).append("=")
									.append(URLEncoder.encode(value.toString(), "UTF-8"));
				url = temp.toString();
			}
		}

		RequestEntity<Object> requestEntity = new RequestEntity<>(body, headers,
				HttpMethod.valueOf(requestMethod.name()), URI.create(url));
		Type type = method.getGenericReturnType();
		if (type == InputStream.class) {
			Resource resource = restTemplate.exchange(requestEntity, Resource.class).getBody();
			if (resource == null)
				return null;
			return resource.getInputStream();
		} else if (type == Void.TYPE) {
			restTemplate.exchange(requestEntity, Resource.class);
			return null;
		} else {
			return restTemplate.exchange(requestEntity, ParameterizedTypeReference.forType(type)).getBody();
		}
	}

}
