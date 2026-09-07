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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Binds named parameters by position instead of by name.
 *
 * <p>The ClickHouse driver resolves every {@code bind(name, value)} with
 * {@code namedParameters.indexOf(name)}, a linear scan. A 1000-row bulk insert carries ~25k
 * parameter names, making binding O(n^2); profiling attributed 65-76% of backend CPU to it. This
 * wrapper reads that list once per statement and binds by index instead, which is O(1).
 *
 * <p>The list comes from the driver, so the mapping is exact. If it cannot be reached the wrapper
 * falls back to named binding and behaviour is unchanged.
 */
@Slf4j
public final class FastBindStatement implements Statement {

    private static final String NAMED_PARAMETERS_FIELD = "namedParameters";

    /** Below this, the driver's linear scan beats building a map, so the statement is left alone. */
    private static final int MIN_PARAMETERS = 64;

    /** Owner and field in one record, so a concurrent lookup cannot observe a mismatched pair. */
    private record FieldCache(Class<?> owner, Field field) {
    }

    private static volatile FieldCache fieldCache;

    /** Escape hatch. Fails closed: anything but "true" restores the driver's named binding. */
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("opik.fastBind", "true"));

    private final Statement delegate;
    private final Map<String, Integer> indexByName;

    private FastBindStatement(Statement delegate, Map<String, Integer> indexByName) {
        this.delegate = delegate;
        this.indexByName = indexByName;
    }

    /** Wraps the statement when positional binding is possible, otherwise returns it untouched. */
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
            Field field = resolveField(target.getClass());
            if (field == null) {
                warnOnce("noField", "field '{}' not found on '{}' - the driver's layout changed",
                        NAMED_PARAMETERS_FIELD, target.getClass().getName());
                return null;
            }
            Object value = field.get(target);
            if (!(value instanceof List<?> names)) {
                warnOnce("badType", "field '{}' is '{}', expected a List", NAMED_PARAMETERS_FIELD,
                        value == null ? "null" : value.getClass().getName());
                return null;
            }
            if (names.isEmpty()) {
                return null;
            }
            if (names.size() < MIN_PARAMETERS) {
                return null;
            }
            Map<String, Integer> index = HashMap.newHashMap(names.size());
            for (int i = 0; i < names.size(); i++) {
                if (!(names.get(i) instanceof String name)) {
                    warnOnce("badElement", "parameter list holds a non-String element at '{}'", i);
                    return null;
                }
                // First occurrence wins, mirroring List.indexOf.
                index.putIfAbsent(name, i);
            }
            announceOnce(names.size());
            return index;
        } catch (Exception e) {
            // Throwable last, no placeholder for it: SLF4J logs the stack trace, not just toString.
            warnOnce("exception", "reflective access failed", e);
            return null;
        }
    }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    /** Each distinct fallback reason once, at WARN - otherwise this switches itself off silently. */
    private static void warnOnce(String key, String message, Object... args) {
        if (WARNED.add(key)) {
            log.warn("Positional bind disabled, falling back to the driver's named binding - " + message, args);
        }
    }

    private static void announceOnce(int parameterCount) {
        if (ANNOUNCED.compareAndSet(false, true)) {
            log.info("Positional bind active (first statement carried '{}' parameters, threshold '{}')",
                    parameterCount, MIN_PARAMETERS);
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

    /** Caches the reflected field per owning class, so setAccessible runs once rather than per statement. */
    private static Field resolveField(Class<?> owner) {
        FieldCache cache = fieldCache;
        if (cache != null && owner.equals(cache.owner())) {
            return cache.field();
        }
        Field field = findField(owner);
        if (field != null) {
            field.setAccessible(true);
        }
        // One publication. A null field is cached too, so a class without it is not re-scanned.
        fieldCache = new FieldCache(owner, field);
        return field;
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
