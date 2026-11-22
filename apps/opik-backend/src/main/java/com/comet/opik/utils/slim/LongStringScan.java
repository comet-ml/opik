package com.comet.opik.utils.slim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;

import java.util.function.BiConsumer;

/**
 * Finds whether any string value of a node is longer than a max length, which is what makes its slim JSON differ
 * from its full JSON. Field names do not count: the slim copy keeps them whole.
 *
 * <p>Objects are walked with {@link ObjectNode#forEachEntry} and arrays by index, so no iterator is allocated per
 * container node: the scan runs on every payload written. It stops descending at the first long string.
 */
final class LongStringScan implements BiConsumer<String, JsonNode> {

    private final int maxLength;
    private boolean found;

    private LongStringScan(int maxLength) {
        this.maxLength = maxLength;
    }

    static boolean hasLongString(@NonNull JsonNode node, int maxLength) {
        var scan = new LongStringScan(maxLength);
        scan.visit(node);
        return scan.found;
    }

    private void visit(JsonNode node) {
        switch (node.getNodeType()) {
            case OBJECT -> ((ObjectNode) node).forEachEntry(this);
            case ARRAY -> {
                for (int index = 0; index < node.size() && !found; index++) {
                    visit(node.get(index));
                }
            }
            case STRING -> found = node.textValue().length() > maxLength;
            case BINARY, BOOLEAN, MISSING, NULL, NUMBER, POJO -> {
            }
        }
    }

    @Override
    public void accept(String name, JsonNode value) {
        if (!found) {
            visit(value);
        }
    }
}
