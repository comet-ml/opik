package com.comet.opik.utils;

import com.comet.opik.api.PromptType;
import com.comet.opik.domain.template.Jinja2Parser;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.domain.template.TemplateParser;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class TemplateParseUtils {
    private static final Map<PromptType, TemplateParser> parsers = new EnumMap<>(PromptType.class);

    static {
        parsers.put(PromptType.MUSTACHE, new MustacheParser());
        parsers.put(PromptType.JINJA2, new Jinja2Parser());
    }

    public static Set<String> extractVariables(@NonNull String template, @NonNull PromptType type) {
        return parsers.get(type).extractVariables(template);
    }

    public static String render(@NonNull String template, @NonNull Map<String, ?> context, @NonNull PromptType type) {
        return parsers.get(type).render(template, context);
    }
}
