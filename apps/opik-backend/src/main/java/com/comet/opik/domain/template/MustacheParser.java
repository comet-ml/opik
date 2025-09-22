package com.comet.opik.domain.template;

import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheException;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.ValueCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class MustacheParser implements TemplateParser {

    public static final MustacheFactory MF = new DefaultMustacheFactory();

    @Override
    public Set<String> extractVariables(String template) {
        Set<String> variables = new HashSet<>();

        if (StringUtils.isBlank(template)) {
            return variables;
        }

        try {
            // Initialize Mustache Factory
            Mustache mustache = MF.compile(new StringReader(template), "template");

            // Get the root node of the template
            Code[] codes = mustache.getCodes();
            collectVariables(codes, variables);

            return variables;
        } catch (MustacheException | IllegalArgumentException ex) {
            log.warn("Failed to parse Mustache template for variable extraction", ex);
            return variables; // Return empty set when parsing fails
        }
    }

    @Override
    public String render(String template, Map<String, ?> context) {
        if (template == null) {
            return "";
        }

        try {
            Mustache mustache = MF.compile(new StringReader(template), "template");
            return renderTemplate(context, mustache);
        } catch (MustacheException ex) {
            log.error("Failed to parse Mustache template for rendering:", ex);
            throw new IllegalArgumentException("Invalid Mustache template", ex);
        }
    }

    private String renderTemplate(Map<String, ?> context, Mustache mustache) {
        try (Writer writer = mustache.execute(new StringWriter(), context)) {
            writer.flush();
            return writer.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render template", e);
        }
    }

    private static void collectVariables(Code[] codes, Set<String> variables) {
        for (Code code : codes) {
            if (Objects.requireNonNull(code) instanceof ValueCode valueCode) {
                variables.add(valueCode.getName());
            } else {
                Optional.ofNullable(code)
                        .map(Code::getCodes)
                        .map(it -> it.length > 0)
                        .ifPresent(it -> collectVariables(code.getCodes(), variables));
            }
        }
    }

}
