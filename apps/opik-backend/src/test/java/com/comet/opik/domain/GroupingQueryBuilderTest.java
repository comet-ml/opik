package com.comet.opik.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.comet.opik.domain.GroupingQueryBuilder.isValidJsonPath;
import static org.junit.jupiter.api.Assertions.*;

class GroupingQueryBuilderTest {

    @Test
    void validKeys() {
        List<String> keys = List.of("$", "$.key", "$['key']", "$[0]", "$[4].model", "$.key[0]['another_key']",
                "$.key1.key2", "$.input.key[4].role", "$.input['key1'][12]['key2']");
        keys.forEach(key -> {
            assertTrue(isValidJsonPath(key));
        });
    }

    @Test
    void invalidKeys() {
        List<String> keys = List.of("$[0].['model name']", "$.key with space", "$[abc]", "$['unterminated]", "model.xx",
                "$.");
        keys.forEach(key -> {
            assertFalse(isValidJsonPath(key));
        });
    }
}