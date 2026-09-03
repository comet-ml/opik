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

    /**
     * Below this many parameters the driver's linear scan is cheaper than building a map, so the
     * statement is left alone. Bulk inserts render tens of thousands of parameters; ordinary
     * queries bind a handful, and wrapping those was measured as a net loss.
     */
    private static final int MIN_PARAMETERS = 64;

    /** The reflected field is the same for every statement; resolve it once, not per statement. */
    private static volatile Field cachedField;
    private static volatile Class<?> cachedFieldOwner;

    /**
     * Escape hatch: anything other than an explicit "true" restores the driver's named binding.
     * Deliberately fails closed - this is the control reached for mid-incident, and
     * {@code -Dopik.fastBind=0} or {@code =off} silently leaving it enabled would send the
     * investigation down the wrong path.
     */
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("opik.fastBind", "true"));

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
            Field field = resolveField(target.getClass());
            if (field == null) {
                warnOnce("noField", "field '{}' not found on {} - the driver's layout changed",
                        NAMED_PARAMETERS_FIELD, target.getClass().getName());
                return null;
            }
            Object value = field.get(target);
            if (!(value instanceof List<?> names)) {
                warnOnce("badType", "field '{}' is {}, expected a List", NAMED_PARAMETERS_FIELD,
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
                    warnOnce("badElement", "parameter list holds a non-String element at {}", i);
                    return null;
                }
                // First occurrence wins, mirroring List.indexOf.
                index.putIfAbsent(name, i);
            }
            announceOnce(names.size());
            return index;
        } catch (Exception e) {
            warnOnce("exception", "reflective access failed: {}", e.toString());
            return null;
        }
    }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    /**
     * Logs each distinct fallback reason once at WARN. Without this the optimization can switch
     * itself off after a driver upgrade and give no signal at all: production runs at INFO, CI
     * stays green, and the only symptom is the CPU regression coming back.
     */
    private static void warnOnce(String key, String message, Object... args) {
        if (WARNED.add(key)) {
            log.warn("Positional bind disabled, falling back to the driver's named binding - " + message, args);
        }
    }

    private static void announceOnce(int parameterCount) {
        if (ANNOUNCED.compareAndSet(false, true)) {
            log.info("Positional bind active (first statement carried {} parameters, threshold {})",
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
        if (owner.equals(cachedFieldOwner)) {
            return cachedField;
        }
        Field field = findField(owner);
        if (field != null) {
            field.setAccessible(true);
        }
        cachedField = field;
        cachedFieldOwner = owner;
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
