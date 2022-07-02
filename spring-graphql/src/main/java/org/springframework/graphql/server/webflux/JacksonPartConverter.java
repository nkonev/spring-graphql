package org.springframework.graphql.server.webflux;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.Part;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Type;

public class JacksonPartConverter implements PartConverter {

    private final ObjectMapper objectMapper;

    private final int maxInMemorySize;

    public JacksonPartConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        maxInMemorySize = 256 * 1024;
    }

    public JacksonPartConverter(ObjectMapper objectMapper, int maxInMemorySize) {
        this.objectMapper = objectMapper;
        this.maxInMemorySize = maxInMemorySize;
    }

    @Override
    public <T> Mono<T> readPart(Part part, Type targetType) {
        JavaType javaType = getJavaType(targetType);

        return DataBufferUtils.join(part.content(), this.maxInMemorySize)
                .flatMap(dataBuffer -> Mono.justOrEmpty(decode(dataBuffer, javaType)));
    }

    private <T> T decode(DataBuffer dataBuffer, JavaType javaType) throws DecodingException {
        try {
            return objectMapper.readValue(dataBuffer.asInputStream(), javaType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private JavaType getJavaType(Type type) {
        return this.objectMapper.constructType(GenericTypeResolver.resolveType(type, (Class<?>)null));
    }
}
