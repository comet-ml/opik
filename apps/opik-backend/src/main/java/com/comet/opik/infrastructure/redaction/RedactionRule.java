package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;

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
public record RedactionRule(@NonNull Pattern pattern, @NonNull String replacement) {

    public static RedactionRule of(@NonNull String regex, @NonNull String replacement) {
        // Stored raw, not quoted: the replacement is appended verbatim below rather than going through
        // appendReplacement, which is what would have un-escaped it. Quoting here as well would emit the
        // escapes themselves - a replacement of \1***@\2 would come out as \\1***@\\2.
        return new RedactionRule(Pattern.compile(regex), replacement);
    }

    /**
     * @return the rewritten text, or {@link MatchBudget#EXCEEDED} when evaluating this rule against this value
     *         exhausted the budget - see {@link MatchBudget} for why that is not treated as "no match".
     */
    public String apply(@NonNull String text, @NonNull MatchBudget budget) {
        try {
            var matcher = pattern.matcher(budget.wrap(text));
            StringBuilder rewritten = null;
            int last = 0;

            while (matcher.find()) {
                if (rewritten == null) {
                    rewritten = new StringBuilder(text.length());
                }
                rewritten.append(text, last, matcher.start()).append(replacement);
                last = matcher.end();
            }

            if (rewritten == null) {
                return text;
            }

            return rewritten.append(text, last, text.length()).toString();
        } catch (MatchBudget.Exceeded exceeded) {
            return MatchBudget.EXCEEDED;
        }
    }
}
