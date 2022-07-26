package org.springframework.graphql.server.webmvc;

import java.lang.reflect.Type;

public interface PartReader {
    <T> T readPart(String param, Type targetType);
}
