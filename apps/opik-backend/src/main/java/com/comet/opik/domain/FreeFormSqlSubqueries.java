package com.comet.opik.domain;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tables of the database a free-form SQL query reads through scalar subqueries, from its resolved
 * {@code EXPLAIN QUERY TREE}. ClickHouse evaluates scalar, {@code IN} and {@code EXISTS} subqueries while analysing
 * the query, so their reads show in neither {@code system.query_log}'s policies nor {@code EXPLAIN}. An {@code IN}
 * or {@code EXISTS} subquery only decides which outer rows are kept, but a scalar one returns its value into the
 * result, so the post-run check needs to tell them apart ({@link FreeFormSqlPolicyCheck}).
 *
 * <p>The query tree resolves CTEs and aliases to the tables they read ({@code TABLE ... table_name: db.t}) and marks
 * every subquery ({@code QUERY|UNION ... is_subquery: 1}); a subquery's role is its position. It is a source under
 * {@code JOIN TREE}, a join side or a UNION's {@code QUERIES}; a filter as an argument of {@code IN} or
 * {@code EXISTS}; and scalar anywhere else, which is where analysis folds it into a {@code CONSTANT}'s
 * {@code EXPRESSION}. A position not recognised as source or filter counts as scalar, so an unknown shape is
 * rejected rather than trusted.
 *
 * <p>A scalar subquery with a large result is not in the tree at all: analysis stores the result and leaves
 * {@code FUNCTION ... function_name: __getScalar} in its place, so its reads cannot be seen. Its presence is reported
 * as {@link ScalarReads#opaque()}, which rejects the query the same way.
 */
@UtilityClass
class FreeFormSqlSubqueries {

    private static final Set<String> FILTER_FUNCTIONS = Set.of("in", "notIn", "globalIn", "globalNotIn", "nullIn",
            "notNullIn", "globalNullIn", "globalNotNullIn", "exists", "notExists");
    private static final Set<String> SOURCE_PARENTS = Set.of("JOIN TREE", "LEFT TABLE EXPRESSION",
            "RIGHT TABLE EXPRESSION", "TABLE EXPRESSION");
    private static final String STORED_SCALAR = "__getScalar";
    private static final Pattern TABLE_NAME = Pattern.compile("(?:^|, )table_name: ([^,]+)");
    private static final Pattern FUNCTION_NAME = Pattern.compile("(?:^|, )function_name: ([^,]+)");
    private static final Pattern SUBQUERY = Pattern.compile("(?:^|, )is_subquery: 1(?:,|$)");

    /** One query tree line: its label ({@code QUERY}, {@code TABLE}, {@code JOIN TREE}, ...) and its attributes. */
    private record Node(String label, String attributes, Node parent, List<Node> children) {

        boolean isSubquery() {
            return (label.equals("QUERY") || label.equals("UNION")) && SUBQUERY.matcher(attributes).find();
        }

        Optional<String> attribute(Pattern pattern) {
            var matcher = pattern.matcher(attributes);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }

        boolean is(String expected) {
            return label.equals(expected);
        }
    }

    /**
     * @param tables the {@code <database>.<table>} names read inside scalar subqueries
     * @param opaque whether a scalar subquery was replaced by its stored result, so its reads are unknown
     */
    record ScalarReads(Set<String> tables, boolean opaque) {

        boolean none() {
            return tables.isEmpty() && !opaque;
        }
    }

    static ScalarReads scalarReads(@NonNull List<String> queryTreeLines, @NonNull String database) {
        var tables = new HashSet<String>();
        boolean[] opaque = {false};
        collect(parse(queryTreeLines), database + ".", tables, opaque);
        return new ScalarReads(Set.copyOf(tables), opaque[0]);
    }

    private static void collect(Node node, String prefix, Set<String> tables, boolean[] opaque) {
        if (node.is("TABLE")) {
            node.attribute(TABLE_NAME)
                    .filter(table -> table.startsWith(prefix) && inScalar(node))
                    .ifPresent(tables::add);
        }
        if (node.is("FUNCTION") && node.attribute(FUNCTION_NAME).filter(STORED_SCALAR::equals).isPresent()) {
            opaque[0] = true;
        }
        node.children().forEach(child -> collect(child, prefix, tables, opaque));
    }

    /** Whether any subquery enclosing {@code node} is used as a value. */
    private static boolean inScalar(Node node) {
        for (var ancestor = node.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor.isSubquery() && !isSource(ancestor) && !isFilter(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSource(Node subquery) {
        var parent = subquery.parent();
        return SOURCE_PARENTS.contains(parent.label())
                || (parent.is("LIST") && parent.parent() != null && parent.parent().is("QUERIES"));
    }

    private static boolean isFilter(Node subquery) {
        var list = subquery.parent();
        var arguments = list.parent();
        var function = arguments == null ? null : arguments.parent();
        return list.is("LIST") && arguments != null && arguments.is("ARGUMENTS") && function != null
                && function.is("FUNCTION")
                && function.attribute(FUNCTION_NAME).filter(FILTER_FUNCTIONS::contains).isPresent();
    }

    /** One node per line, indented two spaces per level: {@code LABEL id: n, key: value, ...} or a section label. */
    private static Node parse(List<String> lines) {
        var root = new Node("", "", null, new ArrayList<>());
        var stack = new ArrayList<Node>();
        var depths = new ArrayList<Integer>();
        stack.add(root);
        depths.add(-1);
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            int depth = line.length() - line.stripLeading().length();
            int id = text.indexOf(" id: ");
            while (depths.getLast() >= depth) {
                stack.removeLast();
                depths.removeLast();
            }
            var node = new Node(id < 0 ? text : text.substring(0, id), id < 0 ? "" : text.substring(id + 1),
                    stack.getLast(), new ArrayList<>());
            stack.getLast().children().add(node);
            stack.add(node);
            depths.add(depth);
        }
        return root;
    }
}
