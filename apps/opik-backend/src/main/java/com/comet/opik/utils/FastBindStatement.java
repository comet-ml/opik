package com.comet.opik.utils;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.Wrapped;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds named parameters by position instead of by name.
 *
 * <p>The ClickHouse R2DBC driver resolves every {@code bind(name, value)} with
 * {@code namedParameters.indexOf(name)} - a linear scan of the parameter list. Our bulk inserts
 * render one placeholder per column per row ({@code :id0 ... :id999}), so a 1000-row batch carries
 * ~25k distinct parameter names and binding becomes O(n^2): ~300M string comparisons per statement.
 * CPU profiling of a bulk experiment upload attributed 65-76% of all backend CPU to
 * {@code ArrayList.indexOfRange} reached from {@code ClickHouseStatement.bind}.
 *
 * <p>The driver's positional {@code bind(int, value)} is O(1), and the index it expects is exactly
 * the position of the name in {@code namedParameters}. This wrapper reads that list once per
 * statement, builds a {@link HashMap}, and turns every later named bind into a positional one.
 *
 * <p>The list is read from the driver rather than re-derived from the SQL, so the mapping is exact
 * by construction. If it cannot be reached - a driver upgrade, an unexpected proxy - the wrapper
 * falls back to the driver's own named binding, so behaviour is unchanged.
 */
@Slf4j
public final class FastBindStatement implements Statement {

    private static final String NAMED_PARAMETERS_FIELD = "namedParameters";

    /** Escape hatch: {@code -Dopik.fastBind=false} restores the driver's named binding. */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("opik.fastBind", "true"));

    private final Statement delegate;
    private final Map<String, Integer> indexByName;

    private FastBindStatement(Statement delegate, Map<String, Integer> indexByName) {
        this.delegate = delegate;
        this.indexByName = indexByName;
    }

    /**
     * Wraps the statement when positional binding is possible, otherwise returns it untouched.
     */
    public static Statement wrap(@NonNull Statement statement) {
        if (!ENABLED) {
            return statement;
        }
        Map<String, Integer> index = resolveIndex(statement);
        return index == null ? statement : new FastBindStatement(statement, index);
    }

    private static Map<String, Integer> resolveIndex(Statement statement) {
        try {
            Object target = unwrap(statement);
            Field field = findField(target.getClass());
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            if (!(value instanceof List<?> names) || names.isEmpty()) {
                return null;
            }
            Map<String, Integer> index = new HashMap<>(names.size() * 2);
            for (int i = 0; i < names.size(); i++) {
                if (!(names.get(i) instanceof String name)) {
                    return null;
                }
                // First occurrence wins, mirroring List.indexOf.
                index.putIfAbsent(name, i);
            }
            return index;
        } catch (Exception e) {
            log.debug("Positional bind unavailable, falling back to named binding", e);
            return null;
        }
    }

    private static Object unwrap(Statement statement) {
        Object target = statement;
        // r2dbc-proxy wraps the real statement; peel it to reach the driver's parameter list.
        for (int i = 0; i < 5 && target instanceof Wrapped<?> wrapped; i++) {
            Object inner = wrapped.unwrap();
            if (inner == null || inner == target) {
                break;
            }
            target = inner;
        }
        return target;
    }

    private static Field findField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(NAMED_PARAMETERS_FIELD);
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }

    @Override
    public Statement bind(int index, @NonNull Object value) {
        delegate.bind(index, value);
        return this;
    }

    @Override
    public Statement bind(@NonNull String name, @NonNull Object value) {
        Integer index = indexByName.get(name);
        if (index == null) {
            delegate.bind(name, value);
        } else {
            delegate.bind(index, value);
        }
        return this;
    }

    @Override
    public Statement bindNull(int index, @NonNull Class<?> type) {
        delegate.bindNull(index, type);
        return this;
    }

    @Override
    public Statement bindNull(@NonNull String name, @NonNull Class<?> type) {
        Integer index = indexByName.get(name);
        if (index == null) {
            delegate.bindNull(name, type);
        } else {
            delegate.bindNull(index, type);
        }
        return this;
    }

    @Override
    public Statement add() {
        delegate.add();
        return this;
    }

    @Override
    public Statement returnGeneratedValues(String... columns) {
        delegate.returnGeneratedValues(columns);
        return this;
    }

    @Override
    public Statement fetchSize(int rows) {
        delegate.fetchSize(rows);
        return this;
    }

    @Override
    public Publisher<? extends Result> execute() {
        return delegate.execute();
    }
}
