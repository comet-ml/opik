package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One find-and-replace rule, mirroring the SDK's RegexRule so a workspace can move the rules it already
 * runs client-side onto the server unchanged.
 * <p>
 * Both halves are prepared once, when the rule set is loaded: the pattern is compiled, and the replacement is
 * quoted so a payload containing {@code $1} or a backslash is treated as literal text rather than a group
 * reference.
 */
public record RedactionRule(@NonNull Pattern pattern, @NonNull String quotedReplacement) {

    public static RedactionRule of(@NonNull String regex, @NonNull String replacement) {
        return new RedactionRule(Pattern.compile(regex), Matcher.quoteReplacement(replacement));
    }

    public String apply(@NonNull String text) {
        return pattern.matcher(text).replaceAll(quotedReplacement);
    }
}
