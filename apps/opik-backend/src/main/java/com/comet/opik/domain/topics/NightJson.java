package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;

/** Lenient reads of a Qwen answer's fields, as the stand's: an entry off the schema is skipped, not an error. */
@UtilityClass
class NightJson {

    static Integer integer(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }

    /** The array's items as integers (null for an item that is not one); empty when it is not an array. */
    static List<Integer> integers(JsonNode node) {
        var out = new ArrayList<Integer>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(integer(item)));
        }
        return out;
    }

    /** The array's object items; empty when it is not an array. */
    static List<JsonNode> objects(JsonNode node) {
        var out = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isObject()) {
                    out.add(item);
                }
            });
        }
        return out;
    }
}
