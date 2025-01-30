package com.comet.opik.utils;

import com.comet.opik.api.PromptType;
import com.comet.opik.domain.template.Jinja2Parser;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.domain.template.TemplateParser;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class TemplateParseUtils {
    private static final Map<PromptType, TemplateParser> parsers = new HashMap<>();

    static {
        parsers.put(PromptType.MUSTACHE, new MustacheParser());
        parsers.put(PromptType.JINJA2, new Jinja2Parser());
    }

    public static Set<String> extractVariables(String template, PromptType type) {
        return parsers.get(type).extractVariables(template);
    }

    public static String render(String template, Map<String, ?> context, PromptType type) {
        return parsers.get(type).render(template, context);
    }
}
