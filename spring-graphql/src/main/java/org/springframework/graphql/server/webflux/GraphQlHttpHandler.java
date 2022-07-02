/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.server.webflux;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.graphql.server.webmvc.PartHttpInput;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ReactiveHttpInputMessage;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux.fn Handler for GraphQL over HTTP requests.
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @since 1.0.0
 */
public class GraphQlHttpHandler {

	private static final Log logger = LogFactory.getLog(GraphQlHttpHandler.class);

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_PARAMETERIZED_TYPE_REF =
			new ParameterizedTypeReference<Map<String, Object>>() {};

	private static final List<MediaType> SUPPORTED_MEDIA_TYPES =
			Arrays.asList(MediaType.APPLICATION_GRAPHQL, MediaType.APPLICATION_JSON);

	private final WebGraphQlHandler graphQlHandler;

	/**
	 * Create a new instance.
	 * @param graphQlHandler common handler for GraphQL over HTTP requests
	 */
	public GraphQlHttpHandler(WebGraphQlHandler graphQlHandler) {
		Assert.notNull(graphQlHandler, "WebGraphQlHandler is required");
		this.graphQlHandler = graphQlHandler;
	}

	/**
	 * Handle GraphQL requests over HTTP.
	 * @param serverRequest the incoming HTTP request
	 * @return the HTTP response
	 */
	public Mono<ServerResponse> handleRequest(ServerRequest serverRequest) {
		return serverRequest.bodyToMono(MAP_PARAMETERIZED_TYPE_REF)
				.flatMap(body -> {
					WebGraphQlRequest graphQlRequest = new WebGraphQlRequest(
							serverRequest.uri(), serverRequest.headers().asHttpHeaders(), body,
							serverRequest.exchange().getRequest().getId(),
							serverRequest.exchange().getLocaleContext().getLocale());
					if (logger.isDebugEnabled()) {
						logger.debug("Executing: " + graphQlRequest);
					}
					return this.graphQlHandler.handleRequest(graphQlRequest);
				})
				.flatMap(response -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Execution complete");
					}
					ServerResponse.BodyBuilder builder = ServerResponse.ok();
					builder.headers(headers -> headers.putAll(response.getResponseHeaders()));
					builder.contentType(selectResponseMediaType(serverRequest));
					return builder.bodyValue(response.toMap());
				});
	}

	public Mono<ServerResponse> handleMultipartRequest(ServerRequest serverRequest) {
		return serverRequest.multipartData()
			.flatMap(multipartMultiMap -> {
				Map<String, Part> allParts = multipartMultiMap.toSingleValueMap();

				Optional<Part> operation = Optional.ofNullable(allParts.get("operations"));
				Optional<Part> mapParam = Optional.ofNullable(allParts.get("map"));

				Map<String, Object> inputQuery = operation
					.map(part -> this.<Map<String, Object>>readPartToMap(
						part,
						MAP_PARAMETERIZED_TYPE_REF.getType(),
						serverRequest.messageReaders()
					))
					.orElse(new HashMap<>());

				final Map<String, Object> queryVariables = getFromMapOrEmpty(inputQuery, "variables");
				final Map<String, Object> extensions = getFromMapOrEmpty(inputQuery, "extensions");

				// TODO code here

				String query = (String) inputQuery.get("query");
				String opName = (String) inputQuery.get("operationName");

				WebGraphQlRequest graphQlRequest = new WebGraphQlRequest(
					serverRequest.uri(), serverRequest.headers().asHttpHeaders(),
					query,
					opName,
					queryVariables,
					extensions,
					serverRequest.exchange().getRequest().getId(),
					serverRequest.exchange().getLocaleContext().getLocale());

				if (logger.isDebugEnabled()) {
					logger.debug("Executing: " + graphQlRequest);
				}
				return this.graphQlHandler.handleRequest(graphQlRequest);
			})
			.flatMap(response -> {
				if (logger.isDebugEnabled()) {
					logger.debug("Execution complete");
				}
				ServerResponse.BodyBuilder builder = ServerResponse.ok();
				builder.headers(headers -> headers.putAll(response.getResponseHeaders()));
				builder.contentType(selectResponseMediaType(serverRequest));
				return builder.bodyValue(response.toMap());
			});
	}

	private <T> T readPartToMap(
		Part part,
		Type bodyType,
		List<HttpMessageReader<?>> messageConverters
	) {
		// this code from DefaultServerRequest.bodyToMono()
		Class<?> bodyClass = Map.class;
		MediaType contentType = MediaType.APPLICATION_JSON;
		ReactiveHttpInputMessage inputMessage = new ReactiveHttpInputMessage(part, contentType);
		try {
			for (HttpMessageReader<?> messageConverter : messageConverters) {
				if (messageConverter instanceof GenericHttpMessageConverter) {
					GenericHttpMessageConverter<T> genericMessageConverter =
						(GenericHttpMessageConverter<T>) messageConverter;
					if (genericMessageConverter.canRead(bodyType, bodyClass, contentType)) {
						return genericMessageConverter.read(bodyType, bodyClass, inputMessage);
					}
				}
				if (messageConverter.canRead(bodyClass, contentType)) {
					HttpMessageConverter<T> theConverter =
						(HttpMessageConverter<T>) messageConverter;
					Class<? extends T> clazz = (Class<? extends T>) bodyClass;
					return theConverter.read(clazz, inputMessage);
				}
			}
		} catch (Exception e) {
			throw new ServerWebInputException("Unable to read type " + bodyType, null, e);
		}
		throw new ServerWebInputException("Unable to find converter for type " + bodyType);
	}

	private Map<String, Object> getFromMapOrEmpty(Map<String, Object> input, String key) {
		if (input.containsKey(key)) {
			return (Map<String, Object>)input.get(key);
		} else {
			return new HashMap<>();
		}
	}

	private static MediaType selectResponseMediaType(ServerRequest serverRequest) {
		for (MediaType accepted : serverRequest.headers().accept()) {
			if (SUPPORTED_MEDIA_TYPES.contains(accepted)) {
				return accepted;
			}
		}
		return MediaType.APPLICATION_JSON;
	}

}
