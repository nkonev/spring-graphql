package org.springframework.graphql.server.webmvc;

import java.lang.reflect.Type;

public interface ParamConverter {
    <T> T readPart(String param, Type targetType);
}
