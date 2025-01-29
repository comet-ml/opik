package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static com.comet.opik.utils.ValidationUtils.SCALE;

public class JsonBigDecimalDeserializer extends NumberDeserializers.BigDecimalDeserializer {

    public static final JsonBigDecimalDeserializer INSTANCE = new JsonBigDecimalDeserializer();

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext context) throws IOException {
        return Optional.ofNullable(super.deserialize(p, context))
                .map(value -> {

                    if (value.scale() > 12) {
                        return value.setScale(SCALE, RoundingMode.HALF_EVEN);
                    }

                    return value;
                })
                .orElse(null);
    }
}
