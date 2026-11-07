package com.comet.opik.api.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.io.UncheckedIOException;

public class MaxJsonEncodedLengthValidator implements ConstraintValidator<MaxJsonEncodedLength, String> {

    private static final JsonMapper ASCII_ONLY_MAPPER = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .build();

    static {
        ASCII_ONLY_MAPPER.getFactory().setCharacterEscapes(new CharacterEscapes() {
            private final int[] asciiEscapes = standardAsciiEscapesForJSON();
            {
                // Python escapes DEL too; Jackson's standard escapes leave it raw.
                asciiEscapes[0x7F] = ESCAPE_STANDARD;
            }

            @Override
            public int[] getEscapeCodesForAscii() {
                return asciiEscapes;
            }

            @Override
            public SerializableString getEscapeSequence(int character) {
                return null;
            }
        });
    }

    private volatile int maxLength;

    @Override
    public void initialize(MaxJsonEncodedLength constraintAnnotation) {
        this.maxLength = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // The encoding is never shorter than the text plus its quotes, so a long text is rejected without writing it.
        return value == null || (value.length() <= maxLength - 2 && encodedLength(value) <= maxLength);
    }

    static int encodedLength(String value) {
        try {
            return ASCII_ONLY_MAPPER.writeValueAsString(value).length();
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
