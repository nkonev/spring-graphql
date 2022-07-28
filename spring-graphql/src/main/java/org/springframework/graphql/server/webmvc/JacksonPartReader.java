package org.springframework.graphql.server.webmvc;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.GenericTypeResolver;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

public class JacksonPartReader implements PartReader {

    private final ObjectMapper objectMapper;

    public JacksonPartReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T readPart(InputStream inputStream, Type targetType) {
        try {
            JavaType javaType = getJavaType(targetType);
            return objectMapper.readValue(inputStream, javaType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private JavaType getJavaType(Type type) {
        return this.objectMapper.constructType(GenericTypeResolver.resolveType(type, (Class<?>)null));
    }
}
