package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;

import java.io.IOException;

import static com.comet.opik.utils.TruncationUtils.TRUNCATION_SUFFIX;

/**
 * Truncates string values as Jackson writes them, to the same chars {@code TruncationUtils.truncateString} returns. Field
 * names are written through {@code writeFieldName}, so they are kept whole.
 */
class TruncatingGenerator extends JsonGeneratorDelegate {

    private final int maxLength;

    /**
     * One buffer per walk for every truncated string, rather than a substring and a concatenation each. Allocated
     * on the first one, as most payloads have none.
     */
    private char[] truncated;

    TruncatingGenerator(JsonGenerator delegate, int maxLength) {
        super(delegate, false);
        this.maxLength = maxLength;
    }

    private char[] truncated() {
        if (truncated == null) {
            truncated = new char[maxLength + TRUNCATION_SUFFIX.length()];
            TRUNCATION_SUFFIX.getChars(0, TRUNCATION_SUFFIX.length(), truncated, maxLength);
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
