package com.comet.opik.utils;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STErrorListener;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.misc.STMessage;

import java.util.List;
import java.util.stream.IntStream;

public class TemplateUtils {

    /**
     * Custom error listener that suppresses attribute-not-defined warnings for attributes
     * used only in conditionals (e.g., {@code <if(trace_id)>}).
     * <p>
     * This is a common pattern in SQL query building where parameters are used to conditionally
     * include query fragments without being rendered directly.
     */
    @Slf4j
    private static class ConditionalAttributeErrorListener implements STErrorListener {

        @Override
        public void compileTimeError(STMessage msg) {
            // Suppress "attribute X isn't defined" errors for attributes used only in conditionals
            // These warnings occur when using patterns like: <if(trace_id)> AND trace_id = :trace_id <endif>
            if (msg.error != null && msg.error.toString().contains("isn't defined")) {
                log.debug("Suppressed StringTemplate compile-time warning: {}", msg.toString());
                return;
            }
            log.warn("StringTemplate compile-time error: {}", msg.toString());
        }

        @Override
        public void runTimeError(STMessage msg) {
            log.error("StringTemplate runtime error: {}", msg.toString());
        }

        @Override
        public void IOError(STMessage msg) {
            log.error("StringTemplate IO error: {}", msg.toString());
        }

        @Override
        public void internalError(STMessage msg) {
            log.error("StringTemplate internal error: {}", msg.toString());
        }
    }

    @RequiredArgsConstructor
    public static class QueryItem {
        public final int index;
        public final boolean hasNext;
    }

    public static List<QueryItem> getQueryItemPlaceHolder(int size) {

        if (size == 0) {
            return List.of();
        }

        return IntStream.range(0, size)
                .mapToObj(i -> new QueryItem(i, i < size - 1))
                .toList();
    }

    public static ST getBatchSql(String sql, int size) {
        var template = new ST(sql);
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(size);

        template.add("items", queryItems);

        return template;
    }

    /**
     * Creates a new ephemeral StringTemplate instance with a dedicated STGroup.
     * <p>
     * This factory method prevents memory leaks by ensuring templates are not cached in the default STGroup.
     * Each ST and STGroup instance created by this method can be garbage collected after use.
     * <p>
     * <b>Why this approach:</b> Using {@code new ST(template)} directly causes templates to be cached
     * in the default STGroup singleton, which prevents garbage collection and leads to unbounded memory growth
     * in high-throughput scenarios.
     *
     * <p><b>Implementation Details:</b>
     * <ul>
     *   <li>Creates a fresh STGroup for each template to avoid caching and ensure thread safety</li>
     *   <li>This approach mirrors the pattern used by the default STGroup internally</li>
     *   <li>Similar implementation strategy used by jdbi3-stringtemplate4 library</li>
     * </ul>
     *
     * <p><b>Performance Considerations:</b>
     * This approach incurs slightly higher CPU overhead compared to sharing ST instances under a common STGroup
     * or using {@code getInstanceOf()} with cached templates. However, the performance impact is negligible in
     * practice, as demonstrated by jdbi3-stringtemplate4's successful use of this pattern in production environments.
     * The benefits of preventing memory leaks and ensuring thread safety far outweigh the minimal CPU cost.
     *
     * <p><b>Alternative Approaches Considered:</b>
     * <ul>
     *   <li><b>STGroupString:</b> Requires defining all parameters upfront, not flexible for our dynamic templates</li>
     *   <li><b>STGroup.defineTemplate with singleton reuse:</b> Requires all formal arguments to be defined
     *       upfront, which is only feasible with regex parsing. Would necessitate changing all templates to
     *       explicitly declare parameters (e.g., {@code init(v) ::= "<if(v)> = <v><endif>"})</li>
     *   <li><b>STGroup.rawDefineTemplate:</b> While it can parse formal arguments automatically, it shares
     *       instances of CompiledST which are not thread-safe</li>
     *   <li><b>External .st files:</b> Would require moving templates out of Java code</li>
     * </ul>
     *
     * @param template the template string to compile
     * @return a new ST instance with its own isolated STGroup
     * @see <a href="https://github.com/comet-ml/opik/issues/2676">Issue #2676: Memory leak in StringTemplate usage</a>
     * @see <a href="https://github.com/antlr/stringtemplate4/issues/61">StringTemplate4 thread safety issue</a>
     * @see <a href="https://github.com/jdbi/jdbi/blob/487e33ffe61e3db5b02e19a4b2c79827b1281831/stringtemplate4/src/main/java/org/jdbi/v3/stringtemplate4/StringTemplateEngine.java#L31-L39">JDBI3 StringTemplateEngine implementation</a>
     */
    public static ST newST(@NonNull String template) {
        var group = new STGroup();
        group.setListener(new ConditionalAttributeErrorListener());
        return new ST(group, template);
    }
}
