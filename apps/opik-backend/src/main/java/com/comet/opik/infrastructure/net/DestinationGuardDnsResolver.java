package com.comet.opik.infrastructure.net;

import lombok.NonNull;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Supplier;

/**
 * Apache HttpClient resolver that applies a {@link DestinationGuard} to the connections of one call only, so a client
 * shared with internal callers checks just the requests that target user-supplied destinations. Within
 * {@link #resolveWith}, every address resolved, retries included, comes from {@link DestinationGuard#lookup(String)},
 * so the addresses checked are the ones connected to; outside it, resolution is the client's default.
 *
 * <p>The guard is bound to a {@link ScopedValue} for that call, which is sound because the classic HttpClient resolves
 * and connects on the thread executing the request, inside the call this scope wraps.
 */
public class DestinationGuardDnsResolver implements DnsResolver {

    /** Request property carrying the {@link DestinationGuard} for a request to a user-supplied destination. */
    public static final String PROPERTY = DestinationGuard.class.getName();

    /** The guard of the call in progress on this thread, bound only within {@link #resolveWith}. */
    private static final ScopedValue<DestinationGuard> GUARD = ScopedValue.newInstance();

    public <T> T resolveWith(DestinationGuard destinationGuard, @NonNull Supplier<T> call) {
        if (destinationGuard == null) {
            return call.get();
        }
        return ScopedValue.where(GUARD, destinationGuard).call(call::get);
    }

    /** Whether the current call is to a user-supplied destination. */
    public boolean inScope() {
        return GUARD.isBound();
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!GUARD.isBound()) {
            return SystemDefaultDnsResolver.INSTANCE.resolve(host);
        }
        return new DestinationGuardDns(GUARD.get()).lookup(host).toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
    }
}
