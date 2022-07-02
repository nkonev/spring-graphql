package org.springframework.graphql.server.webmvc;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.GenericTypeResolver;

import java.io.IOException;
import java.lang.reflect.Type;

public class JacksonParamConverter implements ParamConverter {

    private final ObjectMapper objectMapper;

    public JacksonParamConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T readPart(String param, Type targetType) {
        try {
            JavaType javaType = getJavaType(targetType);
            return objectMapper.readValue(param, javaType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private JavaType getJavaType(Type type) {
        return this.objectMapper.constructType(GenericTypeResolver.resolveType(type, (Class<?>)null));
    }
}
