package com.comet.opik.domain;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tables a free-form SQL query reads through scalar subqueries, from its {@code EXPLAIN AST} (one node per line,
 * indented one space per level). ClickHouse evaluates scalar, {@code IN} and {@code EXISTS} subqueries while
 * analysing the query, so their reads show in neither {@code system.query_log}'s policies nor {@code EXPLAIN}. An
 * {@code IN} or {@code EXISTS} subquery only decides which outer rows are kept, but a scalar one returns its value
 * into the result, so the post-run check needs to tell them apart ({@link FreeFormSqlPolicyCheck}).
 *
 * <p>A subquery is scalar when used as a value: not under {@code FROM}, {@code JOIN} or {@code WITH}, where it is a
 * table source, and not an argument of {@code IN} or {@code EXISTS}. The AST does not name CTEs, so a scalar
 * subquery reading a CTE reports the CTE's name, not the tables behind it.
 */
@UtilityClass
class FreeFormSqlSubqueries {

    private static final Set<String> FILTER_FUNCTIONS = Set.of("in", "notIn", "globalIn", "globalNotIn", "nullIn",
            "notNullIn", "globalNullIn", "globalNotNullIn", "exists");
    private static final Set<String> SOURCE_PARENTS = Set.of("TableExpression", "WithElement");

    private record Node(String type, String name, List<Node> children) {
    }

    /** @return the unqualified names of the tables (or CTEs) read inside scalar subqueries */
    static Set<String> scalarReads(@NonNull List<String> astLines) {
        var scalar = new HashSet<String>();
        walk(parse(astLines), null, false, scalar);
        return Set.copyOf(scalar);
    }

    private static Node parse(List<String> lines) {
        var root = new Node("", "", new ArrayList<>());
        var stack = new ArrayList<Node>();
        var depths = new ArrayList<Integer>();
        stack.add(root);
        depths.add(-1);
        for (String line : lines) {
            String text = line.stripLeading();
            if (text.isEmpty()) {
                continue;
            }
            int depth = line.length() - text.length();
            String[] tokens = text.split("\\s+", 3);
            var node = new Node(tokens[0], tokens.length > 1 && !tokens[1].startsWith("(") ? tokens[1] : "",
                    new ArrayList<>());
            while (depths.getLast() >= depth) {
                stack.removeLast();
                depths.removeLast();
            }
            stack.getLast().children().add(node);
            stack.add(node);
            depths.add(depth);
        }
        return root;
    }

    private static void walk(Node node, Node parent, boolean inScalar, Set<String> scalar) {
        if (node.type().equals("Function") && FILTER_FUNCTIONS.contains(node.name()) && !inScalar) {
            return;
        }
        boolean scalarHere = inScalar
                || (node.type().equals("Subquery") && parent != null && !SOURCE_PARENTS.contains(parent.type()));
        if (scalarHere && node.type().equals("TableIdentifier")) {
            scalar.add(node.name().substring(node.name().lastIndexOf('.') + 1));
        }
        for (Node child : node.children()) {
            walk(child, node, scalarHere, scalar);
        }
    }
}
