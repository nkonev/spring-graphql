package org.springframework.graphql.client;

import org.springframework.graphql.GraphQlRequest;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MultipartBodyCreator {

    public static MultiValueMap<String, ?> convertRequestToMultipartData(GraphQlRequest request) {
        DefaultClientMultipartGraphQlRequest multipartRequest = (DefaultClientMultipartGraphQlRequest) request;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("operations", multipartRequest.toMap());

        int number = 0;
        Map<String, List<String>> partMappings = new HashMap<>();
        for (Map.Entry<String , Object> entry : multipartRequest.getFiles().entrySet()) {
            number++;
            Object resource = entry.getValue();
            String variableName = entry.getKey();
            String partName = "uploadPart" + number;
            builder.part(partName, resource);
            partMappings.put(partName, Collections.singletonList("variables." + variableName));
        }
        builder.part("map", partMappings);
        return builder.build();
    }

}
