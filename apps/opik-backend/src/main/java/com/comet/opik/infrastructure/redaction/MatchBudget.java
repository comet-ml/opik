package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;

/**
 * Bounds the work one regex may do against one value, so a rule cannot be made to run without limit.
 * <p>
 * {@code java.util.regex} backtracks, so the time a pattern takes is a property of the pattern <em>and</em> the
 * input. A rule whose leading quantifier is unanchored — {@code [\w.+-]+@...} is the shape that invites it — is
 * quadratic in the length of an unbroken run of characters it can consume. The rules are written by whoever
 * operates the deployment; the input is whatever a caller last logged, and {@code jacksonConfig.maxStringLength}
 * allows a single value of 100 MB. Measured, that combination reaches seconds of CPU at 32 KB and minutes well
 * before the configured ceiling, on a request thread, for one read.
 * <p>
 * Anchoring the pattern removes it, which is why the configuration says to. Advice is not a control, though, so
 * this makes the bound real: the matcher reads the value through a {@link CharSequence} that counts accesses and
 * aborts past a budget. On real payloads the counting costs about 4%, because the values are short; the runaway
 * case stops in single-digit milliseconds regardless of how long the input is.
 * <p>
 * Aborting {@link #EXCEEDED} means the value is masked whole rather than returned: a rule that could not be
 * evaluated is not evidence that the value is safe to show.
 */
record MatchBudget(long limit) {

    /** Sentinel returned instead of a rewritten value when the budget runs out. */
    static final String EXCEEDED = null;

    /**
     * Generous enough that no realistic value approaches it — a 100 KB value matched linearly costs a few
     * hundred thousand accesses — and small enough that the quadratic case aborts in milliseconds.
     */
    static final MatchBudget DEFAULT = new MatchBudget(2_000_000L);

    /** Thrown and caught inside a single {@code apply}; carries no stack trace, since nothing inspects it. */
    static final class Exceeded extends RuntimeException {
        private static final Exceeded INSTANCE = new Exceeded();

        private Exceeded() {
            super(null, null, false, false);
        }
    }

    CharSequence wrap(@NonNull String value) {
        return new Counted(value, limit);
    }

    /**
     * Deliberately not a {@code String}: the matcher's fast paths are String-specific, and going through
     * {@code charAt} is what makes the accounting possible at all.
     */
    private static final class Counted implements CharSequence {

        private final String delegate;
        private final long limit;
        private long used;

        private Counted(String delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++used > limit) {
                throw Exceeded.INSTANCE;
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate;
        }
    }
}
