package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SpanHierarchyTest {

    /** Renders the bare minimum needed to identify a node in assertions: id + name. */
    private static final Function<Span, ObjectNode> IDENTITY_RENDERER = span -> {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("id", span.id().toString());
        node.put("name", span.name());
        return node;
    };

    @Test
    void emptyListReturnsEmptyArray() {
        var arr = SpanHierarchy.toTree(List.of(), IDENTITY_RENDERER);

        assertThat(arr.isArray()).isTrue();
        assertThat(arr).isEmpty();
    }

    @Test
    void parentNullSpansAreRoots() {
        var a = span("a", null);
        var b = span("b", null);

        var arr = SpanHierarchy.toTree(List.of(a, b), IDENTITY_RENDERER);

        assertThat(arr).hasSize(2);
        assertThat(arr.get(0).get("name").asText()).isEqualTo("a");
        assertThat(arr.get(1).get("name").asText()).isEqualTo("b");
        assertThat(arr.get(0).has("children")).isFalse();
        assertThat(arr.get(1).has("children")).isFalse();
    }

    @Test
    void nestedParentChildIsAttachedUnderChildren() {
        var parent = span("parent", null);
        var child = span("child", parent.id());
        var grandchild = span("grandchild", child.id());

        var arr = SpanHierarchy.toTree(List.of(parent, child, grandchild), IDENTITY_RENDERER);

        assertThat(arr).hasSize(1);
        var root = arr.get(0);
        assertThat(root.get("name").asText()).isEqualTo("parent");
        assertThat(root.get("children")).hasSize(1);
        var c = root.get("children").get(0);
        assertThat(c.get("name").asText()).isEqualTo("child");
        assertThat(c.get("children")).hasSize(1);
        assertThat(c.get("children").get(0).get("name").asText()).isEqualTo("grandchild");
    }

    @Test
    void siblingOrderFollowsInputOrder() {
        var parent = span("parent", null);
        var c1 = span("c1", parent.id());
        var c2 = span("c2", parent.id());
        var c3 = span("c3", parent.id());

        // Pass children in deliberately non-sorted order.
        var arr = SpanHierarchy.toTree(List.of(parent, c2, c1, c3), IDENTITY_RENDERER);

        var children = arr.get(0).get("children");
        assertThat(children).hasSize(3);
        assertThat(children.get(0).get("name").asText()).isEqualTo("c2");
        assertThat(children.get(1).get("name").asText()).isEqualTo("c1");
        assertThat(children.get(2).get("name").asText()).isEqualTo("c3");
    }

    /**
     * The load-bearing invariant: a span whose {@code parentSpanId} is non-null
     * but whose parent is NOT in the input list is promoted to a root rather
     * than dropped. Without this, partial trace fetches (e.g., only spans within
     * a window) would silently lose orphan branches.
     */
    @Test
    void orphanSpansAreRootsWhenParentMissing() {
        var missingParent = UUID.randomUUID();
        var orphan = span("orphan", missingParent);
        var realRoot = span("real_root", null);
        var orphansChild = span("orphans_child", orphan.id());

        var arr = SpanHierarchy.toTree(List.of(orphan, realRoot, orphansChild), IDENTITY_RENDERER);

        assertThat(arr).hasSize(2);
        // Orphan came first in input -> root[0]; then realRoot -> root[1].
        assertThat(arr.get(0).get("name").asText()).isEqualTo("orphan");
        assertThat(arr.get(1).get("name").asText()).isEqualTo("real_root");
        // orphan's own child still attaches under it (parent IS in working set).
        assertThat(arr.get(0).get("children")).hasSize(1);
        assertThat(arr.get(0).get("children").get(0).get("name").asText()).isEqualTo("orphans_child");
        assertThat(arr.get(1).has("children")).isFalse();
    }

    @Test
    void rendererIsAppliedPerSpanAndChildrenKeyIsAddedByHelper() {
        var parent = span("parent", null);
        var child = span("child", parent.id());

        // Custom renderer adds a "marker" key — the helper must preserve it
        // and only ADD "children" on top.
        Function<Span, ObjectNode> markerRenderer = s -> {
            ObjectNode node = JsonUtils.getMapper().createObjectNode();
            node.put("id", s.id().toString());
            node.put("marker", "x-" + s.name());
            return node;
        };

        var arr = SpanHierarchy.toTree(List.of(parent, child), markerRenderer);

        var root = arr.get(0);
        assertThat(root.get("marker").asText()).isEqualTo("x-parent");
        assertThat(root.has("children")).isTrue();
        assertThat(root.get("children").get(0).get("marker").asText()).isEqualTo("x-child");
        // Helper must not have added "children" to leaf nodes.
        assertThat(root.get("children").get(0).has("children")).isFalse();
    }

    @Test
    void leafSpansHaveNoChildrenKey() {
        var only = span("only", null);

        var arr = SpanHierarchy.toTree(List.of(only), IDENTITY_RENDERER);

        assertThat(arr.get(0).has("children")).isFalse();
    }

    private static Span span(String name, UUID parentSpanId) {
        return Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .parentSpanId(parentSpanId)
                .name(name)
                .type(SpanType.general)
                .startTime(Instant.now())
                .build();
    }
}
