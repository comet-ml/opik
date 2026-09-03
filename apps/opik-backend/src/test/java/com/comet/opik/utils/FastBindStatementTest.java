package com.comet.opik.utils;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.Wrapped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper's correctness rests on one claim: the index it derives for a parameter name equals
 * the index the driver would have derived with {@code namedParameters.indexOf(name)}. If that ever
 * diverges the values still type-check and ClickHouse still accepts the INSERT - the rows are just
 * written into the wrong columns, silently. These tests pin that equivalence, and pin the fallbacks
 * that are otherwise invisible at runtime.
 */
class FastBindStatementTest {

    /**
     * Stands in for the driver's statement. The field name and shape mirror
     * {@code ClickHouseStatement}: the list comes from a LinkedHashMap keySet, so it is ordered by
     * first appearance and free of duplicates.
     */
    static class FakeClickHouseStatement implements Statement {

        @SuppressWarnings("unused") // read reflectively, exactly as the real driver's field is
        private final List<String> namedParameters;

        final List<String> boundByName = new ArrayList<>();
        final List<Integer> boundByIndex = new ArrayList<>();
        final List<Integer> nulledByIndex = new ArrayList<>();
        final List<String> nulledByName = new ArrayList<>();

        FakeClickHouseStatement(List<String> rendered) {
            // Mirror ClickHouseParameterizedQuery: distinct names, first-appearance order.
            this.namedParameters = new ArrayList<>(new LinkedHashMap<>(
                    rendered.stream().collect(java.util.stream.Collectors.toMap(
                            n -> n, n -> n, (a, b) -> a, LinkedHashMap::new)))
                    .keySet());
        }

        List<String> parameters() {
            return namedParameters;
        }

        @Override
        public Statement bind(int index, Object value) {
            boundByIndex.add(index);
            return this;
        }

        @Override
        public Statement bind(String name, Object value) {
            boundByName.add(name);
            return this;
        }

        @Override
        public Statement bindNull(int index, Class<?> type) {
            nulledByIndex.add(index);
            return this;
        }

        @Override
        public Statement bindNull(String name, Class<?> type) {
            nulledByName.add(name);
            return this;
        }

        @Override
        public Statement add() {
            return this;
        }

        @Override
        public Statement returnGeneratedValues(String... columns) {
            return this;
        }

        @Override
        public Statement fetchSize(int rows) {
            return this;
        }

        @Override
        public Publisher<? extends Result> execute() {
            return null;
        }
    }

    /** Mimics r2dbc-proxy, which the real statement is wrapped in before we ever see it. */
    record ProxyStatement(Statement delegate) implements Statement, Wrapped<Statement> {

        @Override
        public Statement unwrap() {
            return delegate;
        }

        @Override
        public Statement bind(int index, Object value) {
            return delegate.bind(index, value);
        }

        @Override
        public Statement bind(String name, Object value) {
            return delegate.bind(name, value);
        }

        @Override
        public Statement bindNull(int index, Class<?> type) {
            return delegate.bindNull(index, type);
        }

        @Override
        public Statement bindNull(String name, Class<?> type) {
            return delegate.bindNull(name, type);
        }

        @Override
        public Statement add() {
            return delegate.add();
        }

        @Override
        public Statement returnGeneratedValues(String... columns) {
            return delegate.returnGeneratedValues(columns);
        }

        @Override
        public Statement fetchSize(int rows) {
            return delegate.fetchSize(rows);
        }

        @Override
        public Publisher<? extends Result> execute() {
            return delegate.execute();
        }
    }

    /** A bulk insert's placeholders: per-row names plus one repeated name, as SpanDAO renders. */
    private static List<String> bulkParameters(int rows) {
        List<String> rendered = new ArrayList<>();
        IntStream.range(0, rows).forEach(i -> {
            rendered.add("id" + i);
            rendered.add("workspace_id"); // repeats every row, deduped by the driver
            rendered.add("name" + i);
            rendered.add("input" + i);
        });
        return rendered;
    }

    @Test
    void bindsByIndex_atTheSameIndexTheDriverWouldHaveResolved() {
        var fake = new FakeClickHouseStatement(bulkParameters(100));
        var wrapped = FastBindStatement.wrap(fake);

        assertThat(wrapped).isNotSameAs(fake);

        // Every name must land on exactly indexOf(name) - the property the whole change rests on.
        var expected = fake.parameters();
        for (String name : expected) {
            wrapped.bind(name, "v");
        }

        assertThat(fake.boundByIndex)
                .isEqualTo(IntStream.range(0, expected.size()).boxed().toList());
        assertThat(fake.boundByName).isEmpty();
    }

    /**
     * "workspace_id" is the interesting one: it is rendered once per row, so the driver's list holds
     * it once and every row's bind must resolve to that single first occurrence.
     */
    @ParameterizedTest
    @ValueSource(strings = {"id0", "workspace_id", "name50", "input99"})
    void bindResolvesToIndexOf(String name) {
        var fake = new FakeClickHouseStatement(bulkParameters(100));

        FastBindStatement.wrap(fake).bind(name, "v");

        assertThat(fake.boundByIndex).containsExactly(fake.parameters().indexOf(name));
        assertThat(fake.boundByName).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id0", "workspace_id", "name50", "input99"})
    void bindNullResolvesToIndexOf(String name) {
        var fake = new FakeClickHouseStatement(bulkParameters(100));

        FastBindStatement.wrap(fake).bindNull(name, String.class);

        assertThat(fake.nulledByIndex).containsExactly(fake.parameters().indexOf(name));
        assertThat(fake.nulledByName).isEmpty();
    }

    @Test
    void unknownNameFallsThroughToTheDriver_preservingItsException() {
        var fake = new FakeClickHouseStatement(bulkParameters(100));
        var wrapped = FastBindStatement.wrap(fake);

        wrapped.bind("not_a_parameter", "v");

        assertThat(fake.boundByName).containsExactly("not_a_parameter");
        assertThat(fake.boundByIndex).isEmpty();
    }

    @Test
    void unwrapsTheProxy_soTheDriverStatementIsFound() {
        var fake = new FakeClickHouseStatement(bulkParameters(100));
        var wrapped = FastBindStatement.wrap(new ProxyStatement(fake));

        wrapped.bind("id3", "v");

        assertThat(fake.boundByIndex).containsExactly(fake.parameters().indexOf("id3"));
    }

    private static Stream<Arguments> statementsLeftUntouched() {
        return Stream.of(
                // Below the threshold the driver's scan over a short list beats building a map.
                Arguments.of("below the parameter threshold",
                        (Supplier<Statement>) () -> new FakeClickHouseStatement(
                                List.of("workspace_id", "id", "name"))),
                // No driver field to read: fall back rather than guess.
                Arguments.of("not a driver statement", (Supplier<Statement>) FastBindStatementTest::foreignStatement));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statementsLeftUntouched")
    void returnsTheOriginalStatement(String reason, Supplier<Statement> statement) {
        var original = statement.get();

        assertThat(FastBindStatement.wrap(original)).isSameAs(original);
    }

    private static Statement foreignStatement() {
        return new Statement() {
            @Override
            public Statement bind(int index, Object value) {
                return this;
            }

            @Override
            public Statement bind(String name, Object value) {
                return this;
            }

            @Override
            public Statement bindNull(int index, Class<?> type) {
                return this;
            }

            @Override
            public Statement bindNull(String name, Class<?> type) {
                return this;
            }

            @Override
            public Statement add() {
                return this;
            }

            @Override
            public Statement returnGeneratedValues(String... columns) {
                return this;
            }

            @Override
            public Statement fetchSize(int rows) {
                return this;
            }

            @Override
            public Publisher<? extends Result> execute() {
                return null;
            }
        };
    }
}
