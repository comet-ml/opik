package com.comet.opik.infrastructure.net;

import lombok.experimental.StandardException;

import java.net.UnknownHostException;

/**
 * Thrown by {@link DestinationGuardDns} for a refused host, so callers can tell a refusal from a host that doesn't
 * resolve.
 */
@StandardException
public class RefusedHostException extends UnknownHostException {
}
