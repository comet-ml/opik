package com.comet.opik.utils;

import java.io.OutputStream;

/**
 * {@link OutputStream} that holds no buffer and aborts as soon as more than {@code budget} bytes have been
 * written, by throwing {@link BudgetExceededException}. Lets an oversized value be rejected without
 * serializing it in full, so the check costs O(1) transient heap and stops at the limit rather than at the
 * end of the payload. Single-use: create a fresh instance per measurement.
 */
final class BudgetedOutputStream extends OutputStream {

    /**
     * Signals that the budget was exceeded. Used for control flow only, so it carries no message,
     * suppression or stack trace.
     */
    static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException() {
            super(null, null, false, false);
        }
    }

    private final long budget;
    private long count;

    BudgetedOutputStream(long budget) {
        this.budget = budget;
    }

    @Override
    public void write(int b) {
        add(1L);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        add(length);
    }

    private void add(long written) {
        count += written;
        if (count > budget) {
            throw new BudgetExceededException();
        }
    }
}
