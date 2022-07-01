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
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.*;

import javax.servlet.ServletException;
import javax.servlet.http.Part;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.graphql.server.support.MultipartVariableMapper;
import reactor.core.publisher.Mono;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
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

    private static final ParameterizedTypeReference<Map<String, List<String>>> LIST_PARAMETERIZED_TYPE_REF =
            new ParameterizedTypeReference<Map<String, List<String>>>() {};

	private static final List<MediaType> SUPPORTED_MEDIA_TYPES =
			Arrays.asList(MediaType.APPLICATION_GRAPHQL, MediaType.APPLICATION_JSON);

    private static final List<MediaType> PARTS_SUPPORTED_MEDIA_TYPES =
            Collections.singletonList(MediaType.APPLICATION_JSON);

    private final IdGenerator idGenerator = new AlternativeJdkIdGenerator();

	private final WebGraphQlHandler graphQlHandler;

	/**
	 * Create a new instance.
	 * @param graphQlHandler common handler for GraphQL over HTTP requests
     * @deprecated Use GraphQlHttpHandler(WebGraphQlHandler graphQlHandler, ObjectMapper objectMapper) instead.
	 */
    public GraphQlHttpHandler(WebGraphQlHandler graphQlHandler) {
		Assert.notNull(graphQlHandler, "WebGraphQlHandler is required");
		this.graphQlHandler = graphQlHandler;
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

    private <T> T read(
            Part part,
            Type bodyType,
            List<org.springframework.http.converter.HttpMessageConverter<?>> messageConverters
    ) {
        Class<?> bodyClass = Map.class;
        MediaType contentType =
                Optional.ofNullable(part.getContentType())
                        .map(MediaType::parseMediaType)
                        .orElse(MediaType.APPLICATION_JSON);
        HttpInputMessage inputMessage = new PartHttpInput(part, contentType);
        try {
            for (HttpMessageConverter<?> messageConverter : messageConverters) {
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
            throw new RuntimeException("Unable to read type " + bodyType, e);
        }
        throw new RuntimeException("Unable to find converter for type " + bodyType);
    }

	public ServerResponse handleMultipartRequest(ServerRequest serverRequest) throws ServletException {
        Map<String, Part> allParts = getMultipartMap(serverRequest);

        Optional<Part> operation = Optional.ofNullable(allParts.get("operations"));
        Optional<Part> mapParam = Optional.ofNullable(allParts.get("map"));

        Map<String, Object> inputQuery = operation
            .map(part -> this.<Map<String, Object>>read(
                    part,
                    MAP_PARAMETERIZED_TYPE_REF.getType(),
                    serverRequest.messageConverters()
            ))
            .orElse(Collections.emptyMap());

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

		Map<String, List<String>> fileMapInput =
                mapParam.map(part -> this.<Map<String, List<String>>>read(
                    part,
                    LIST_PARAMETERIZED_TYPE_REF.getType(),
                    serverRequest.messageConverters()
                ))
                .orElse(Collections.emptyMap());

		fileMapInput.forEach((String fileKey, List<String> objectPaths) -> {
			Part file = allParts.get(fileKey);
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

	private static Map<String, Part> getMultipartMap(ServerRequest request) {
		try {
            return request.multipartData().toSingleValueMap();
		}
		catch (RuntimeException | IOException | ServletException ex) {
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

class PartHttpInput implements HttpInputMessage {

    private final Part part;

    private final HttpHeaders headers;

    public PartHttpInput(Part part, MediaType mediaType) {
        this.part = part;
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(mediaType);
        this.headers = httpHeaders;
    }

    @Override
    public InputStream getBody() throws IOException {
        return part.getInputStream();
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
