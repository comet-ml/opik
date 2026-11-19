package com.comet.opik.api.validation;

import com.google.common.net.InetAddresses;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

public class SecureHttpUrlValidator implements ConstraintValidator<SecureHttpUrl, String> {

    @Override
    public boolean isValid(String url, ConstraintValidatorContext context) {
        if (StringUtils.isBlank(url)) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            return false;
        }
        var host = uri.getHost();
        if (host == null) {
            return false;
        }
        return "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(host));
    }

    /** Never resolves the name: only {@code localhost} itself and loopback address literals count. */
    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || (InetAddresses.isUriInetAddress(host) && InetAddresses.forUriString(host).isLoopbackAddress());
    }
}
