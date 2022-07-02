package org.springframework.graphql.server.webmvc;

import javax.servlet.http.Part;
import java.lang.reflect.Type;

public interface PartConverter {
    <T> T readPart(Part part, Type targetType);
}
