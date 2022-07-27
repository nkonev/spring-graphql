package org.springframework.graphql.client;

import org.springframework.graphql.GraphQlRequest;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public final class MultipartBodyCreator {

    public static MultiValueMap<String, ?> convertRequestToMultipartData(GraphQlRequest request) {
        MultipartClientGraphQlRequest multipartRequest = (MultipartClientGraphQlRequest) request;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("operations", multipartRequest.toMap());

        Map<String, List<String>> partMappings = new HashMap<>();
        createFilePartsAndMapping(multipartRequest.getFiles(), partMappings, builder::part);

        builder.part("map", partMappings);
        return builder.build();
    }

    public static void createFilePartsAndMapping(
            Map<String, ?> fileRequestEntries,
            Map<String, List<String>> partMappings,
            BiConsumer<String, Object> partConsumer) {
        int number = 0;
        for (Map.Entry<String, ?> entry : fileRequestEntries.entrySet()) {
            number++;
            Object resource = entry.getValue();
            String variableName = entry.getKey();
            String partName = "uploadPart" + number;
            partConsumer.accept(partName, resource);
            partMappings.put(partName, Collections.singletonList("variables." + variableName));
        }
    }

}
