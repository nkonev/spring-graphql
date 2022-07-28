package org.springframework.graphql.server.webmvc;

import java.io.InputStream;
import java.lang.reflect.Type;

public interface PartReader {
    <T> T readPart(InputStream inputStream, Type targetType);
}
