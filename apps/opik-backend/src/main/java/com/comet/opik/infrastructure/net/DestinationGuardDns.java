package com.comet.opik.infrastructure.net;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * OkHttp connects only to the addresses its {@link Dns} returns, and resolves every redirect hop through it, so this
 * applies the {@link DestinationGuard} to each connection actually made, with no second lookup in between.
 */
@RequiredArgsConstructor
public class DestinationGuardDns implements Dns {

    private final @NonNull DestinationGuard destinationGuard;

    /** A client builder whose every connection goes through the guard. */
    public static OkHttpClient.Builder clientBuilder(@NonNull DestinationGuard destinationGuard) {
        return new OkHttpClient.Builder().dns(new DestinationGuardDns(destinationGuard));
    }

    public static boolean isRefused(@NonNull Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream().anyMatch(RefusedHostException.class::isInstance);
    }

    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        try {
            return destinationGuard.lookup(hostname);
        } catch (DestinationGuardException exception) {
            if (exception.getCause() instanceof UnknownHostException unresolved) {
                throw unresolved;
            }
            throw new RefusedHostException(exception.getMessage(), exception);
        }
    }
}
