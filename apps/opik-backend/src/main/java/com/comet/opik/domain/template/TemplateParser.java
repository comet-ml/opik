package com.comet.opik.domain.template;

import java.util.Map;
import java.util.Set;

public interface TemplateParser {

    Set<String> extractVariables(String template);

    String render(String template, Map<String, ?> context);
}
