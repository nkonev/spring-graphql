/*
 * Copyright 2002-2022 the original author or authors.
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

import java.util.*;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import graphql.schema.GraphQLScalarType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.coercing.webflux.UploadCoercing;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import org.springframework.graphql.GraphQlSetup;
import org.springframework.http.MediaType;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GraphQlHttpHandler}.
 * @author Rossen Stoyanchev
 */
public class GraphQlHttpHandlerTests {

	private final GraphQlHttpHandler greetingHandler = GraphQlSetup.schemaContent("type Query { greeting: String }")
			.queryFetcher("greeting", (env) -> "Hello").toHttpHandlerWebFlux();


	@Test
	void shouldProduceApplicationJsonByDefault() {
		MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/")
				.contentType(MediaType.APPLICATION_JSON).accept(MediaType.ALL).build();

		MockServerHttpResponse httpResponse = handleRequest(
				httpRequest, this.greetingHandler, Collections.singletonMap("query", "{greeting}"));

		assertThat(httpResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}

    @Test
    void shouldPassFile() {
        GraphQlHttpHandler handler = GraphQlSetup.schemaContent(
                        "type Query { ping: String } \n" +
                        "scalar Upload\n" +
                        "type FileUploadResult {\n" +
                        "  id: String!\n" +
                        "}\n" +
                        "type Mutation {\n" +
                        "    fileUpload(file: Upload!): FileUploadResult!\n" +
                        "}")
                .queryFetcher("fileUpload", (env) -> "{\"id\": \"uuid-1\"}")
                .runtimeWiring(builder -> builder.scalar(GraphQLScalarType.newScalar()
                        .name("Upload")
                        .coercing(new UploadCoercing())
                        .build()))
                .toHttpHandlerWebFlux();

        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.ALL).build();

        MockServerHttpResponse httpResponse = handleMultipartRequest(
                httpRequest, handler, Collections.singletonMap("query", "mutation FileUpload($file: Upload!) {fileUpload(file: $file){id}}"),
                Collections.singletonMap("variables", Collections.singletonMap("file", null)),
                Collections.singletonMap("file", new ClassPathResource("/foo.txt"))
        );

        assertThat(httpResponse.getBodyAsString().block())
                .isEqualTo("{\"data\":{\"fileUpload\":{\"id\":\"uuid-1\"}}}");    }

	@Test
	void shouldProduceApplicationGraphQl() {
		MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/")
				.contentType(MediaType.APPLICATION_GRAPHQL).accept(MediaType.APPLICATION_GRAPHQL).build();

		MockServerHttpResponse httpResponse = handleRequest(
				httpRequest, this.greetingHandler, Collections.singletonMap("query", "{greeting}"));

		assertThat(httpResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_GRAPHQL);
	}

	@Test
	void shouldProduceApplicationJson() {
		MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/")
				.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).build();

		MockServerHttpResponse httpResponse = handleRequest(
				httpRequest, this.greetingHandler, Collections.singletonMap("query", "{greeting}"));

		assertThat(httpResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}

	@Test
	void locale() {
		GraphQlHttpHandler handler = GraphQlSetup.schemaContent("type Query { greeting: String }")
				.queryFetcher("greeting", (env) -> "Hello in " + env.getLocale())
				.toHttpHandlerWebFlux();

		MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/")
				.contentType(MediaType.APPLICATION_GRAPHQL).accept(MediaType.APPLICATION_GRAPHQL).acceptLanguageAsLocales(Locale.FRENCH).build();

		MockServerHttpResponse httpResponse = handleRequest(
				httpRequest, handler, Collections.singletonMap("query", "{greeting}"));

		assertThat(httpResponse.getBodyAsString().block())
				.isEqualTo("{\"data\":{\"greeting\":\"Hello in fr\"}}");
	}

	@Test
	void shouldSetExecutionId() {
		GraphQlHttpHandler handler = GraphQlSetup.schemaContent("type Query { showId: String }")
				.queryFetcher("showId", (env) -> env.getExecutionId().toString())
				.toHttpHandlerWebFlux();

		MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/")
				.contentType(MediaType.APPLICATION_GRAPHQL).accept(MediaType.APPLICATION_GRAPHQL).build();

		MockServerHttpResponse httpResponse = handleRequest(
				httpRequest, handler, Collections.singletonMap("query", "{showId}"));

		DocumentContext document = JsonPath.parse(httpResponse.getBodyAsString().block());
		String id = document.read("data.showId", String.class);
		assertThat(id).isEqualTo(httpRequest.getId());
	}

	private MockServerHttpResponse handleRequest(
			MockServerHttpRequest httpRequest, GraphQlHttpHandler handler, Map<String, String> body) {

		MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

		MockServerRequest serverRequest = MockServerRequest.builder()
				.exchange(exchange)
				.uri(((ServerWebExchange) exchange).getRequest().getURI())
				.method(((ServerWebExchange) exchange).getRequest().getMethod())
				.headers(((ServerWebExchange) exchange).getRequest().getHeaders())
				.body(Mono.just((Object) body));

		handler.handleRequest(serverRequest)
				.flatMap(response -> response.writeTo(exchange, new DefaultContext()))
				.block();

		return exchange.getResponse();
	}

    private MockServerHttpResponse handleMultipartRequest(
            MockServerHttpRequest httpRequest, GraphQlHttpHandler handler, Map<String, String> body,
            Map<String, Object> variables, Map<String, Object> files) {

        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        Map<String, Object> map = new LinkedHashMap<>(3);
        map.put("query", body);
        map.put("variables", variables);
        LinkedMultiValueMap<String, Object> builder = new LinkedMultiValueMap<>();
        builder.add("operations", map);

        int number = 0;
        Map<String, List<String>> mappings = new HashMap<>();
        for (Map.Entry<String , Object> entry : files.entrySet()) {
            number++;
            Object resource = entry.getValue();
            String variableName = entry.getKey();
            String partName = "uploadPart" + number;
            builder.add(partName, resource);
            mappings.put(partName, Collections.singletonList("variables." + variableName));
        }
        builder.add("map", mappings);
//        MultiValueMap<String, HttpEntity<?>> multipartRequest = builder.build();

        MockServerRequest serverRequest = MockServerRequest.builder()
                .exchange(exchange)
                .uri(((ServerWebExchange) exchange).getRequest().getURI())
                .method(((ServerWebExchange) exchange).getRequest().getMethod())
                .headers(((ServerWebExchange) exchange).getRequest().getHeaders())
                .body(BodyInserters.fromMultipartData(builder));

        handler.handleMultipartRequest(serverRequest)
                .flatMap(response -> response.writeTo(exchange, new DefaultContext()))
                .block();

        return exchange.getResponse();
    }


	private static class DefaultContext implements ServerResponse.Context {

		@Override
		public List<HttpMessageWriter<?>> messageWriters() {
			return Collections.singletonList(new EncoderHttpMessageWriter<>(new Jackson2JsonEncoder()));
		}

		@Override
		public List<ViewResolver> viewResolvers() {
			return Collections.emptyList();
		}

	}

}
