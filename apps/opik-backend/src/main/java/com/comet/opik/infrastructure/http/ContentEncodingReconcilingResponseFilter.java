package com.comet.opik.infrastructure.http;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.NonNull;

import java.util.Locale;
import java.util.Set;

/**
 * Reconciles response headers after Apache HttpClient 5 has already decompressed the body, so that
 * Jersey's {@code GZipDecoder} does not decompress it a second time.
 *
 * <p>When {@code jerseyClient.gzipEnabled} is {@code true}, Dropwizard wires BOTH Apache HttpClient's
 * transparent content decompression (advertises {@code Accept-Encoding} and decodes the entity) AND
 * Jersey's {@code GZipDecoder} reader interceptor. {@code DropwizardApacheConnector} copies the raw
 * HTTP/1.1 response headers — including {@code Content-Encoding: gzip} — into the Jersey response while
 * handing over the entity stream that HttpClient has already decoded. {@code GZipDecoder} then sees the
 * stale {@code Content-Encoding} header and wraps the already-plain stream in a {@code GZIPInputStream},
 * so {@code readEntity} throws {@code java.util.zip.ZipException: Not in GZIP format}.
 *
 * <p>This filter runs before entity reading and drops the {@code Content-Encoding} (and now-incorrect
 * {@code Content-Length}) header for the encodings HttpClient decodes transparently, leaving a single,
 * correct decode. {@code Accept-Encoding: gzip} is still sent on the wire, so compression is preserved.
 * It is only registered when gzip is enabled (see {@link HttpModule#buildClient}), which is exactly when
 * HttpClient performs the transparent decode.
 */
public class ContentEncodingReconcilingResponseFilter implements ClientResponseFilter {

    // Content codings that Apache HttpClient 5's ContentCompressionExec decodes transparently by default.
    private static final Set<String> TRANSPARENTLY_DECODED = Set.of("gzip", "x-gzip", "deflate");

    @Override
    public void filter(ClientRequestContext requestContext, @NonNull ClientResponseContext responseContext) {
        String contentEncoding = responseContext.getHeaderString(HttpHeaders.CONTENT_ENCODING);

        if (contentEncoding != null
                && TRANSPARENTLY_DECODED.contains(contentEncoding.strip().toLowerCase(Locale.ROOT))) {
            var headers = responseContext.getHeaders();
            headers.remove(HttpHeaders.CONTENT_ENCODING);
            headers.remove(HttpHeaders.CONTENT_LENGTH);
        }
    }
}
