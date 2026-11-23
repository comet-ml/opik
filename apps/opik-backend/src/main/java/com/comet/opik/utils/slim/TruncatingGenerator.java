package com.comet.opik.utils.slim;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import lombok.NonNull;

import java.io.IOException;

/**
 * Cuts string values as Jackson writes them: a value longer than the max length is cut to it and followed by
 * {@link #SUFFIX}. Field names are written through {@code writeFieldName}, so they are kept whole.
 */
class TruncatingGenerator extends JsonGeneratorDelegate {

    static final String SUFFIX = "...";

    private final int maxLength;

    /**
     * One buffer per walk for every cut string, rather than a substring and a concatenation each. Allocated on the
     * first one, as most payloads have none.
     */
    private char[] truncated;

    TruncatingGenerator(@NonNull JsonGenerator delegate, int maxLength) {
        super(delegate, false);
        this.maxLength = maxLength;
    }

    /** Whether any string value was cut, so the text written differs from the full JSON. */
    boolean hasTruncated() {
        return truncated != null;
    }

    private char[] truncated() {
        if (truncated == null) {
            truncated = new char[maxLength + SUFFIX.length()];
            SUFFIX.getChars(0, SUFFIX.length(), truncated, maxLength);
        }
        return truncated;
    }

    @Override
    public void writeString(String text) throws IOException {
        if (text == null || text.length() <= maxLength) {
            delegate.writeString(text);
            return;
        }
        char[] buffer = truncated();
        text.getChars(0, maxLength, buffer, 0);
        delegate.writeString(buffer, 0, buffer.length);
    }

    @Override
    public void writeString(char[] text, int offset, int length) throws IOException {
        if (length <= maxLength) {
            delegate.writeString(text, offset, length);
            return;
        }
        char[] buffer = truncated();
        System.arraycopy(text, offset, buffer, 0, maxLength);
        delegate.writeString(buffer, 0, buffer.length);
    }

    @Override
    public void writeString(SerializableString text) throws IOException {
        writeString(text.getValue());
    }
}
