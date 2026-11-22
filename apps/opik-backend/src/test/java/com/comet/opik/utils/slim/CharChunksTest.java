package com.comet.opik.utils.slim;

import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class CharChunksTest {

    private static String writtenRaw(CharChunks chunks) throws IOException {
        var out = new StringWriter();
        try (var generator = JsonUtils.getMapper().createGenerator(out)) {
            generator.writeStartArray();
            chunks.writeTo(generator);
            generator.writeEndArray();
        }
        return out.toString();
    }

    @Test
    @DisplayName("writes back every char, across chunks of every size, as one raw value")
    void writesBackAcrossChunks() throws IOException {
        var chunks = new CharChunks();
        // Every chunk size, then several chunks of the largest.
        String text = RandomStringUtils.secure().next(
                CharChunks.MAX_CHUNK * 4 + RandomUtils.secure().randomInt(0, CharChunks.FIRST_CHUNK));
        var expected = new StringBuilder();
        int offset = 0;
        while (offset < text.length()) {
            int length = Math.min(RandomUtils.secure().randomInt(1, 3000), text.length() - offset);
            switch (offset % 3) {
                case 0 -> chunks.write(text, offset, length);
                case 1 -> chunks.write(text.toCharArray(), offset, length);
                default -> {
                    length = 1;
                    chunks.write(text.charAt(offset));
                }
            }
            expected.append(text, offset, offset + length);
            offset += length;
        }

        assertThat(writtenRaw(chunks)).isEqualTo("[%s]".formatted(expected));
    }
}
