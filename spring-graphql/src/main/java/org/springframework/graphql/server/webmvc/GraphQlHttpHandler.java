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

package org.springframework.graphql.server.webmvc;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.ServletException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.graphql.server.support.MultipartVariableMapper;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.AbstractMultipartHttpServletRequest;
import reactor.core.publisher.Mono;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.http.MediaType;
import org.springframework.util.AlternativeJdkIdGenerator;
import org.springframework.util.Assert;
import org.springframework.util.IdGenerator;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * GraphQL handler to expose as a WebMvc.fn endpoint via
 * {@link org.springframework.web.servlet.function.RouterFunctions}.
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

	private final IdGenerator idGenerator = new AlternativeJdkIdGenerator();

	private final WebGraphQlHandler graphQlHandler;

    private final ObjectMapper objectMapper;

	/**
	 * Create a new instance.
	 * @param graphQlHandler common handler for GraphQL over HTTP requests
     * @deprecated Use GraphQlHttpHandler(WebGraphQlHandler graphQlHandler, ObjectMapper objectMapper) instead.
	 */
    @Deprecated
    public GraphQlHttpHandler(WebGraphQlHandler graphQlHandler) {
		Assert.notNull(graphQlHandler, "WebGraphQlHandler is required");
		this.graphQlHandler = graphQlHandler;
        this.objectMapper = new ObjectMapper();
	}

    /**
     * Create a new instance.
     * @param graphQlHandler common handler for GraphQL over HTTP requests
     * @param objectMapper ObjectMapper used for parsing form parts
     */
    public GraphQlHttpHandler(WebGraphQlHandler graphQlHandler, ObjectMapper objectMapper) {
        Assert.notNull(graphQlHandler, "WebGraphQlHandler is required");
        Assert.notNull(objectMapper, "ObjectMapper is required");
        this.graphQlHandler = graphQlHandler;
        this.objectMapper = objectMapper;
    }

	/**
	 * Handle GraphQL requests over HTTP.
	 * @param serverRequest the incoming HTTP request
	 * @return the HTTP response
	 * @throws ServletException may be raised when reading the request body, e.g.
	 * {@link HttpMediaTypeNotSupportedException}.
	 */
	public ServerResponse handleRequest(ServerRequest serverRequest) throws ServletException {

		WebGraphQlRequest graphQlRequest = new WebGraphQlRequest(
				serverRequest.uri(), serverRequest.headers().asHttpHeaders(), readBody(serverRequest),
				this.idGenerator.generateId().toString(), LocaleContextHolder.getLocale());

		if (logger.isDebugEnabled()) {
			logger.debug("Executing: " + graphQlRequest);
		}

		Mono<ServerResponse> responseMono = this.graphQlHandler.handleRequest(graphQlRequest)
				.map(response -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Execution complete");
					}
					ServerResponse.BodyBuilder builder = ServerResponse.ok();
					builder.headers(headers -> headers.putAll(response.getResponseHeaders()));
					builder.contentType(selectResponseMediaType(serverRequest));
					return builder.body(response.toMap());
				});

		return ServerResponse.async(responseMono);
	}

	public ServerResponse handleMultipartRequest(ServerRequest serverRequest) throws ServletException {
		Optional<String> operation = serverRequest.param("operations");
		Optional<String> mapParam = serverRequest.param("map");
		Map<String, Object> inputQuery = readJson(operation, new TypeReference<Map<String, Object>>() {});
		final Map<String, Object> queryVariables;
		if (inputQuery.containsKey("variables")) {
			queryVariables = (Map<String, Object>)inputQuery.get("variables");
		} else {
			queryVariables = new HashMap<>();
		}
		Map<String, Object> extensions = new HashMap<>();
		if (inputQuery.containsKey("extensions")) {
			extensions = (Map<String, Object>)inputQuery.get("extensions");
		}

		Map<String, MultipartFile> fileParams = getMultipartMap(serverRequest);
		Map<String, List<String>> fileMapInput = readJson(mapParam, new TypeReference<Map<String, List<String>>>() {});
		fileMapInput.forEach((String fileKey, List<String> objectPaths) -> {
			MultipartFile file = fileParams.get(fileKey);
			if (file != null) {
				objectPaths.forEach((String objectPath) -> {
					MultipartVariableMapper.mapVariable(
						objectPath,
						queryVariables,
						file
					);
				});
			}
		});

		String query = (String) inputQuery.get("query");
		String opName = (String) inputQuery.get("operationName");

		WebGraphQlRequest graphQlRequest = new WebGraphQlRequest(
			serverRequest.uri(), serverRequest.headers().asHttpHeaders(),
			query,
			opName,
			queryVariables,
			extensions,
			this.idGenerator.generateId().toString(), LocaleContextHolder.getLocale());

		if (logger.isDebugEnabled()) {
			logger.debug("Executing: " + graphQlRequest);
		}

		Mono<ServerResponse> responseMono = this.graphQlHandler.handleRequest(graphQlRequest)
			.map(response -> {
				if (logger.isDebugEnabled()) {
					logger.debug("Execution complete");
				}
				ServerResponse.BodyBuilder builder = ServerResponse.ok();
				builder.headers(headers -> headers.putAll(response.getResponseHeaders()));
				builder.contentType(selectResponseMediaType(serverRequest));
				return builder.body(response.toMap());
			});

		return ServerResponse.async(responseMono);
	}

	private <T> T readJson(Optional<String> string, TypeReference<T> t) {
		Map<String, Object> map = new HashMap<>();
		if (string.isPresent()) {
			try {
				return objectMapper.readValue(string.get(), t);
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		}
		return (T)map;
	}

	private static Map<String, MultipartFile> getMultipartMap(ServerRequest request) {
		try {
			AbstractMultipartHttpServletRequest abstractMultipartHttpServletRequest =
				(AbstractMultipartHttpServletRequest) request.servletRequest();
			return abstractMultipartHttpServletRequest.getFileMap();
		}
		catch (RuntimeException ex) {
			throw new ServerWebInputException("Error while reading request parts", null, ex);
		}
	}

	private static Map<String, Object> readBody(ServerRequest request) throws ServletException {
		try {
			return request.body(MAP_PARAMETERIZED_TYPE_REF);
		}
		catch (IOException ex) {
			throw new ServerWebInputException("I/O error while reading request body", null, ex);
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
