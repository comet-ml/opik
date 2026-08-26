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
 * case stops after the budget's worth of accesses however long the input is, which is why the budget has a
 * ceiling as well as a floor - measured, an access costs about 2 ns, so what the ceiling buys is the difference
 * between about a second and about twenty.
 * <p>
 * Aborting {@link #EXCEEDED} means the value is masked whole rather than returned: a rule that could not be
 * evaluated is not evidence that the value is safe to show.
 */
record MatchBudget(long limit) {

    /** Sentinel returned instead of a rewritten value when the budget runs out. */
    static final String EXCEEDED = null;

    /**
     * Per-character allowance. A linear scan costs about one access per character per rule, so this leaves two
     * orders of magnitude of headroom for backtracking that stays proportional, while a quadratic pattern
     * exceeds it almost immediately - at 32 KB the quadratic case wants ~500M accesses against an allowance of
     * ~3M.
     * <p>
     * Scaled rather than absolute because a purely absolute ceiling fails closed on size alone:
     * {@code jacksonConfig.maxStringLength} permits 100 MB, so a fixed 2M budget would destroy a perfectly
     * anchored 5 MB value that contains no match at all, purely from the forward scan.
     */
    private static final long ALLOWANCE_PER_CHARACTER = 100L;

    /** Floor, so a short value still gets room for legitimate backtracking. */
    private static final long MINIMUM = 100_000L;

    /**
     * Ceiling, so the largest permitted value cannot buy a correspondingly large amount of CPU.
     * <p>
     * The scaled allowance alone is unbounded in the only variable a caller controls: {@code maxStringLength}
     * permits a 100 MB value, which at 100 accesses per character is 10.5 billion accesses - measured at ~2 ns
     * each, some 20 seconds on a request thread, per rule, for one read. Capping the count caps the time.
     * <p>
     * Set where it cannot reach a legitimate rule. Measured over 10 MB values, the anchored patterns the
     * configuration recommends - e-mail, telephone, card, JWT - consume between 1.0 and 3.2 accesses per
     * character, matching or not. A billion leaves ~9.5 per character at the largest value {@code
     * maxStringLength} allows and does not bind at all below 10 MB, where the scaled allowance is the smaller of
     * the two; the worst case it admits is about two seconds rather than twenty.
     */
    private static final long CEILING = 1_000_000_000L;

    static MatchBudget forValue(@NonNull String value) {
        return new MatchBudget(limitFor(value.length()));
    }

    /** Split out so the bounds can be asserted without allocating a 100 MB string to assert them against. */
    static long limitFor(int length) {
        return Math.min(CEILING, Math.max(MINIMUM, ALLOWANCE_PER_CHARACTER * length));
    }

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
