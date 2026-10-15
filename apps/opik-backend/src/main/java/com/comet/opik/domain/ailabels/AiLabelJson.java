package com.comet.opik.domain.ailabels;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.TreeMap;

/**
 * The canonical JSON of a rendered state, and its exact size in UTF-8 bytes without serializing it.
 *
 * <p>Canonical means every object's keys are sorted (the nodes are backed by a {@link TreeMap}, so any serializer
 * emits them in order) and no text value holds a lone surrogate: a parsed {@code "\ud800"} becomes U+FFFD, since
 * downstream it either fails UTF-8 encoding or is silently replaced. The bytes are those of
 * {@link #toCanonicalString(JsonNode)}, i.e. Jackson's {@code String} output encoded as UTF-8, which is the text a
 * model sees once the state is embedded in a prompt.
 */
@UtilityClass
class AiLabelJson {

    static final JsonNodeFactory NODE_FACTORY = new CanonicalNodeFactory();

    /** The bytes a code point takes inside a JSON string, for the ASCII range: escapes included. */
    private static final int[] ASCII_BYTES = new int[128];

    static {
        for (int c = 0; c < ASCII_BYTES.length; c++) {
            ASCII_BYTES[c] = switch (c) {
                case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                default -> c < 0x20 ? 6 : 1;
            };
        }
    }

    /** Blank is absent. A value that isn't JSON is kept as text rather than dropped. */
    static JsonNode parse(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return JsonUtils.getMapper().reader().with(NODE_FACTORY).readTree(value);
        } catch (JsonProcessingException e) {
            return NODE_FACTORY.textNode(value);
        }
    }

    static String toCanonicalString(JsonNode node) {
        try {
            return JsonUtils.getMapper().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The serialized size of a non-container node; a string's quotes included. */
    static long scalarBytes(JsonNode node) {
        return switch (node.getNodeType()) {
            case STRING -> stringBytes(node.textValue());
            // Jackson writes NaN and the infinities as quoted strings.
            case NUMBER -> node.asText().length() + ((node.isDouble() || node.isFloat())
                    && !Double.isFinite(node.doubleValue()) ? 2 : 0);
            case BOOLEAN -> node.booleanValue() ? 4 : 5;
            case NULL -> 4;
            default -> throw new IllegalArgumentException(
                    "Unexpected node type in a rendered state, type '%s'".formatted(node.getNodeType()));
        };
    }

    /** The serialized size of a string value or key, its quotes included. */
    static long stringBytes(String value) {
        long bytes = 2;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes += ASCII_BYTES[c];
            } else if (c < 0x800) {
                bytes += 2;
            } else
                if (Character.isHighSurrogate(c) && i + 1 < length
                        && Character.isLowSurrogate(value.charAt(i + 1))) {
                            bytes += 4;
                            i++;
                        } else {
                            // A lone surrogate (only possible in a key) is encoded as the single replacement byte '?'.
                            bytes += Character.isSurrogate(c) ? 1 : 3;
                        }
        }
        return bytes;
    }

    static int codePointBytes(int codePoint) {
        if (codePoint < 0x80) {
            return ASCII_BYTES[codePoint];
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return Character.isSurrogate((char) codePoint) ? 1 : 3;
        }
        return 4;
    }

    private static String replaceLoneSurrogates(String value) {
        StringBuilder sanitized = null;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!Character.isSurrogate(c)) {
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
                continue;
            }
            if (sanitized == null) {
                sanitized = new StringBuilder(value);
            }
            sanitized.setCharAt(i, '�');
        }
        return sanitized == null ? value : sanitized.toString();
    }

    private static final class CanonicalNodeFactory extends JsonNodeFactory {

        private CanonicalNodeFactory() {
            super(false);
        }

        @Override
        public ObjectNode objectNode() {
            return new ObjectNode(this, new TreeMap<>());
        }

        @Override
        public TextNode textNode(String text) {
            return super.textNode(text == null ? null : replaceLoneSurrogates(text));
        }
    }
}
