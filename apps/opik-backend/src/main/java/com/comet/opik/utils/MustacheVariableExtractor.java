package com.comet.opik.utils;

import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.ValueCode;
import lombok.experimental.UtilityClass;

import java.io.StringReader;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@UtilityClass
public class MustacheVariableExtractor {

    public static final MustacheFactory MF = new DefaultMustacheFactory();

    public static Set<String> extractVariables(String template) {
        Set<String> variables = new HashSet<>();

        // Initialize Mustache Factory
        Mustache mustache = MF.compile(new StringReader(template), "template");

        // Get th e root node of the template
        Code[] codes = mustache.getCodes();
        collectVariables(codes, variables);

        return variables;
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
