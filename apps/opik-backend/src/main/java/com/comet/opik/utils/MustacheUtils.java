package com.comet.opik.utils;

import com.comet.opik.api.PromptType;
import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.ValueCode;
import lombok.experimental.UtilityClass;

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

@UtilityClass
public class MustacheUtils {

    public static final MustacheFactory MF = new DefaultMustacheFactory();

    public static Set<String> extractVariables(String template, PromptType type) {
        if (type == PromptType.JINJA2) {
            return Set.of();
        }

        return extractVariables(template);
    }

    public static Set<String> extractVariables(String template) {
        Set<String> variables = new HashSet<>();

        // Initialize Mustache Factory
        Mustache mustache = MF.compile(new StringReader(template), "template");

        // Get the root node of the template
        Code[] codes = mustache.getCodes();
        collectVariables(codes, variables);

        return variables;
    }

    public static String render(String template, Map<String, ?> context) {

        Mustache mustache = MF.compile(new StringReader(template), "template");

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
