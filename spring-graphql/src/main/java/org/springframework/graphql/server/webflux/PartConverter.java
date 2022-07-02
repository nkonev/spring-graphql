package org.springframework.graphql.server.webflux;

import org.springframework.http.codec.multipart.Part;
import reactor.core.publisher.Mono;

import java.lang.reflect.Type;

public interface PartConverter {
    <T> Mono<T> readPart(Part part, Type targetType);
}
