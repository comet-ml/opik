package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.utils.JsonUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Seeded trace and span rows, shaped as the render reads return them: JSON fields as stored text, times in
 * microseconds. The same seed always gives the same rows, so the rows can back golden files.
 */
final class AiLabelRenderFixtures {

    // Literals, not Math.pow: pow may differ in the last bit between CPUs, which would make golden files platform-bound.
    private static final double[] POWERS_OF_TEN = {1e-15, 1e-14, 1e-13, 1e-12, 1e-11, 1e-10, 1e-9, 1e-8, 1e-7, 1e-6,
            1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
            1e14};

    /** Code points from several scripts, plus the characters JSON escapes. */
    private static final int[][] SCRIPTS = {
            {'a', 'z'}, {'A', 'Z'}, {'0', '9'}, {' ', ' '},
            {0xE0, 0xFF}, // Latin-1 accents: 2 bytes
            {0x0410, 0x044F}, // Cyrillic: 2 bytes
            {0x4E00, 0x4FFF}, // CJK: 3 bytes
            {0x0905, 0x0939}, // Devanagari: 3 bytes
            {0x1F600, 0x1F64F}, // emoji: 4 bytes, a surrogate pair
            {'"', '"'}, {'\\', '\\'}, {'\n', '\n'}, {'\t', '\t'}, {0x01, 0x1F}, // escaped
    };

    static final Instant BASE_TIME = Instant.parse("2026-10-15T12:00:00.123456Z");

    private final Random random;
    private final boolean mixedScripts;

    AiLabelRenderFixtures(long seed, boolean mixedScripts) {
        this.random = new Random(seed);
        this.mixedScripts = mixedScripts;
    }

    static TraceRef refOf(TraceRow trace) {
        return new TraceRef(trace.projectId(), trace.id(), BASE_TIME);
    }

    TraceRow trace(int ioChars) {
        return TraceRow.builder()
                .id(uuid())
                .projectId(uuid())
                .name("trace " + text(8))
                .input(json(ioChars))
                .output(json(ioChars))
                .build();
    }

    /**
     * A random tree of {@code count} spans at most {@code maxDepth} deep, in no particular order. Some start at the
     * same instant, so the id tiebreaker matters.
     */
    List<SpanRow> spans(TraceRow trace, int count, int maxDepth, int ioChars) {
        List<SpanRow> spans = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int parent = spans.isEmpty() || random.nextInt(8) == 0 ? -1 : random.nextInt(spans.size());
            if (parent >= 0 && depths.get(parent) >= maxDepth) {
                parent = -1;
            }
            depths.add(parent < 0 ? 1 : depths.get(parent) + 1);
            spans.add(span(trace, parent < 0 ? null : spans.get(parent).id(), ioChars));
        }
        return spans;
    }

    SpanRow span(TraceRow trace, String parentSpanId, int ioChars) {
        return SpanRow.builder()
                .traceId(trace.id())
                .id(uuid().toString())
                .parentSpanId(parentSpanId)
                .name("span " + text(random.nextInt(4) == 0 ? 100 : 10))
                .type(List.of("general", "tool", "llm", "guardrail").get(random.nextInt(4)))
                .input(json(ioChars))
                .output(json(ioChars))
                .startTime(BASE_TIME.plus(random.nextInt(50) * 1000L, ChronoUnit.MICROS))
                .build();
    }

    /** JSON text of roughly {@code chars} characters of string content. */
    String json(int chars) {
        if (chars <= 0) {
            return "";
        }
        return JsonUtils.getMapper().valueToTree(value(chars, 0)).toString();
    }

    private Object value(int chars, int depth) {
        return switch (depth > 3 ? 0 : random.nextInt(4)) {
            case 0 -> text(chars);
            case 1 -> {
                var object = new LinkedHashMap<String, Object>();
                object.put("role", "user");
                object.put("count", random.nextInt(1000));
                object.put("score", random.nextDouble() * POWERS_OF_TEN[random.nextInt(POWERS_OF_TEN.length)]);
                object.put("flag", random.nextBoolean());
                object.put("nothing", null);
                object.put("z_" + text(3), value(chars / 2, depth + 1));
                object.put("a_" + text(3), value(chars - chars / 2, depth + 1));
                yield object;
            }
            case 2 -> {
                var array = new ArrayList<Object>();
                int parts = 1 + random.nextInt(3);
                for (int i = 0; i < parts; i++) {
                    array.add(value(chars / parts, depth + 1));
                }
                array.add(random.nextLong());
                yield array;
            }
            default -> {
                // Ordered maps: earlier turns keep their stored text as is, so its key order must be stable.
                var messages = new ArrayList<Object>();
                messages.add(message("system", text(chars / 3)));
                messages.add(message("user", text(chars - chars / 3)));
                yield new LinkedHashMap<>(Map.of("messages", messages));
            }
        };
    }

    private static Map<String, Object> message(String role, String content) {
        var message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    String text(int codePoints) {
        var text = new StringBuilder();
        for (int i = 0; i < codePoints; i++) {
            if (!mixedScripts) {
                text.append((char) ('a' + random.nextInt(26)));
                continue;
            }
            int[] script = SCRIPTS[random.nextInt(SCRIPTS.length)];
            text.appendCodePoint(script[0] + random.nextInt(script[1] - script[0] + 1));
        }
        return text.toString();
    }

    private UUID uuid() {
        return new UUID(random.nextLong(), random.nextLong());
    }
}
