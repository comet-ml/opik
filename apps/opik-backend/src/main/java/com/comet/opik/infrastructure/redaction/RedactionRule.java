package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One find-and-replace rule: a pattern, and the literal text that replaces each match.
 * <p>
 * The replacement is literal, unlike the SDK's RegexRule, where Python's {@code re.sub} expands
 * backreferences — so a partial-mask rule that produced {@code j***@example.com} client-side emits the text
 * {@code \1***@\2} here. A rule set moves across unchanged only if its replacements are plain text.
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
