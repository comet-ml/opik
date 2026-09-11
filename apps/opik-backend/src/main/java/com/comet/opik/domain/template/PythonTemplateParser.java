package com.comet.opik.domain.template;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and renders Python-style format string templates ({var}).
 * Supports simple variable substitution where {variable_name} is replaced
 * with the corresponding value from the context map.
 */
@Slf4j
public class PythonTemplateParser implements TemplateParser {

    // Matches {word_chars} not preceded by { and not followed by }
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(?<!\\{)\\{(\\w+)}(?!})");

    @Override
    public Set<String> extractVariables(String template) {
        Set<String> variables = new HashSet<>();

        if (StringUtils.isBlank(template)) {
            return variables;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }

        return variables;
    }

    @Override
    public String render(String template, Map<String, ?> context) {
        if (template == null) {
            return "";
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = context.get(varName);
            String replacement = value != null ? value.toString() : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
