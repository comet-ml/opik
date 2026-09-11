package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared parent-map / roots / recursive-walk plumbing for the two places that
 * render a span tree: {@link SpanTreeSerializer} (overview tool, content-rich
 * per-node) and {@link TraceCompressor} (SKELETON tier, identity-only per-node).
 *
 * <p>Root rule: a span is a root if its {@code parentSpanId} is {@code null}
 * <strong>or</strong> if the parent is not present in {@code spans} (orphan
 * promotion). This invariant is non-obvious and easy to break by accident if
 * each call site maintains its own copy — which is why it lives here.
 */
@UtilityClass
class SpanHierarchy {

    /**
     * Renders {@code spans} as a JSON array of root trees. Each span's per-node
     * content is produced by {@code renderer}; this helper attaches recursive
     * children under a {@code "children"} key.
     *
     * <p>Order: children appear in the input order (a {@link LinkedHashMap}
     * preserves first-insertion order per parent).
     */
    ArrayNode toTree(@NonNull List<Span> spans, @NonNull Function<Span, ObjectNode> renderer) {
        var arr = JsonUtils.getMapper().createArrayNode();
        if (spans.isEmpty()) {
            return arr;
        }

        Set<UUID> ids = new HashSet<>();
        for (var s : spans) {
            ids.add(s.id());
        }

        Map<UUID, List<Span>> childrenByParent = new LinkedHashMap<>();
        List<Span> roots = new ArrayList<>();
        for (var s : spans) {
            if (s.parentSpanId() == null || !ids.contains(s.parentSpanId())) {
                roots.add(s);
            } else {
                childrenByParent.computeIfAbsent(s.parentSpanId(), k -> new ArrayList<>()).add(s);
            }
        }

        for (var root : roots) {
            arr.add(buildNode(root, childrenByParent, renderer));
        }
        return arr;
    }

    private static ObjectNode buildNode(Span span, Map<UUID, List<Span>> childrenByParent,
            Function<Span, ObjectNode> renderer) {
        ObjectNode node = renderer.apply(span);
        var children = childrenByParent.get(span.id());
        if (children != null && !children.isEmpty()) {
            ArrayNode childrenArr = JsonUtils.getMapper().createArrayNode();
            for (var child : children) {
                childrenArr.add(buildNode(child, childrenByParent, renderer));
            }
            node.set("children", childrenArr);
        }
        return node;
    }
}