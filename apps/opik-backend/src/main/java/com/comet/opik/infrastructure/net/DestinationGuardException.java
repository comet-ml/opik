package com.comet.opik.infrastructure.net;

/**
 * A user-supplied outbound destination was refused by {@link DestinationGuard}. The message is
 * user-facing: it names the URL the user configured, never the address it resolved to.
 */
public class DestinationGuardException extends RuntimeException {

    public DestinationGuardException(String message) {
        super(message);
    }
}
