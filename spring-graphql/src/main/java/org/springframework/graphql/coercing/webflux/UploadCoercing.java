package org.springframework.graphql.coercing.webflux;

import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;

public class UploadCoercing implements Coercing<Object, Part> {

    @Override
    public Part serialize(Object dataFetcherResult) throws CoercingSerializeException {
        throw new CoercingSerializeException("Upload is an input-only type");
    }

    @Override
    public Object parseValue(Object input) throws CoercingParseValueException {
        if (input instanceof FilePart) {
            return input;
        }
        throw new CoercingParseValueException(
                String.format("Expected 'FilePart' or 'Part' like object but was '%s'.", input != null ? input.getClass() : null)
        );
    }

    @Override
    public Object parseLiteral(Object input) throws CoercingParseLiteralException {
        throw new CoercingParseLiteralException("Parsing literal of 'MultipartFile' is not supported");
    }
}

