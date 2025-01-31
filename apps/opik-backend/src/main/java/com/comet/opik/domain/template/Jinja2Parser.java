package com.comet.opik.domain.template;

import java.util.Map;
import java.util.Set;

public class Jinja2Parser implements TemplateParser {
    @Override
    public Set<String> extractVariables(String template) {
        return Set.of();
    }

    @Override
    public String render(String template, Map<String, ?> context) {
        throw new UnsupportedOperationException("Jinja2 template rendering is not supported");
    }
}
