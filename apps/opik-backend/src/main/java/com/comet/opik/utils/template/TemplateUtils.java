package com.comet.opik.utils.template;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;

import java.util.List;
import java.util.stream.IntStream;

public class TemplateUtils {

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
        var template = newST(sql);
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
        group.setListener(TemplateLogger.INSTANCE);
        return new ST(group, template);
    }
}
