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
 * as {@link SubqueryReads#opaque()}, which rejects the query the same way.
 *
 * <p>The tables read under {@code IN} or {@code EXISTS} and in no scalar subquery are returned too, so the check
 * accepts a read missing from the plan only when the query tree shows it is one of those.
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
     * @param scalar the {@code <database>.<table>} names read inside scalar subqueries
     * @param filter those read under {@code IN} or {@code EXISTS} and in no scalar subquery
     * @param opaque whether a scalar subquery was replaced by its stored result, so its reads are unknown
     */
    record SubqueryReads(@NonNull Set<String> scalar, @NonNull Set<String> filter, boolean opaque) {

        SubqueryReads {
            scalar = Set.copyOf(scalar);
            filter = Set.copyOf(filter);
        }

        /** Nothing known about the query's subqueries: a read missing from the plan is then not accepted. */
        static final SubqueryReads UNKNOWN = new SubqueryReads(Set.of(), Set.of(), false);

        boolean hasScalar() {
            return !scalar.isEmpty() || opaque;
        }
    }

    private enum Role {
        SOURCE,
        FILTER,
        SCALAR
    }

    static SubqueryReads subqueryReads(@NonNull List<String> queryTreeLines, @NonNull String database) {
        var scalar = new HashSet<String>();
        var filter = new HashSet<String>();
        boolean[] opaque = {false};
        collect(parse(queryTreeLines), database + ".", scalar, filter, opaque);
        return new SubqueryReads(scalar, filter, opaque[0]);
    }

    private static void collect(Node node, String prefix, Set<String> scalar, Set<String> filter,
            boolean[] opaque) {
        if (node.is("TABLE")) {
            node.attribute(TABLE_NAME).filter(table -> table.startsWith(prefix)).ifPresent(table -> {
                switch (role(node)) {
                    case SCALAR -> scalar.add(table);
                    case FILTER -> filter.add(table);
                    case SOURCE -> {
                    }
                }
            });
        }
        if (node.is("FUNCTION") && node.attribute(FUNCTION_NAME).filter(STORED_SCALAR::equals).isPresent()) {
            opaque[0] = true;
        }
        node.children().forEach(child -> collect(child, prefix, scalar, filter, opaque));
    }

    /** Scalar if any subquery enclosing {@code node} is used as a value; else filter if any is under IN/EXISTS. */
    private static Role role(Node node) {
        var role = Role.SOURCE;
        for (var ancestor = node.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (!ancestor.isSubquery() || isSource(ancestor)) {
                continue;
            }
            if (!isFilter(ancestor)) {
                return Role.SCALAR;
            }
            role = Role.FILTER;
        }
        return role;
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
