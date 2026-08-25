package com.comet.opik.api;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * A {@link ProviderAuthConfig} that hides whether it currently holds the parsed document (when
 * built from a request) or the AES-GCM ciphertext (when loaded from the database). Decryption is
 * lazy and memoized: rows that are loaded but never consulted — e.g. sibling providers filtered
 * out while resolving a model — never materialize plaintext credentials on the heap.
 *
 * <p>On the wire this is invisible: it serializes as, and deserializes from, the plain
 * {@link ProviderAuthConfig} object.
 */
public final class EncryptedAuthConfig {

    private final String ciphertext;
    private volatile ProviderAuthConfig value;

    private EncryptedAuthConfig(String ciphertext, ProviderAuthConfig value) {
        this.ciphertext = ciphertext;
        this.value = value;
    }

    /** DB path: holds the ciphertext, parses only on first {@link #value()} access. */
    public static EncryptedAuthConfig fromCiphertext(String ciphertext) {
        return ciphertext == null ? null : new EncryptedAuthConfig(ciphertext, null);
    }

    /** Request/in-memory path: already parsed. */
    @JsonCreator
    public static EncryptedAuthConfig of(ProviderAuthConfig value) {
        return value == null ? null : new EncryptedAuthConfig(null, value);
    }

    @JsonValue
    public ProviderAuthConfig value() {
        ProviderAuthConfig parsed = value;
        if (parsed == null) {
            parsed = JsonUtils.readValue(EncryptionUtils.decryptGcm(ciphertext), ProviderAuthConfig.class);
            value = parsed;
        }
        return parsed;
    }

    /** Persistence form; reuses the stored ciphertext when the value was never re-parsed. */
    public String ciphertextOrEncrypt() {
        return ciphertext != null
                ? ciphertext
                : EncryptionUtils.encryptGcm(JsonUtils.writeValueAsString(value));
    }

    /**
     * Stable input for cache-key hashing without decryption. DB-loaded configs hash their
     * ciphertext — identical on every pod until the row is rewritten, which is exactly the
     * "any config save is an instant deployment-wide cache miss" semantic.
     */
    public String hashSource() {
        return ciphertext != null ? ciphertext : JsonUtils.writeValueAsString(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EncryptedAuthConfig that && Objects.equals(value(), that.value());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value());
    }

    @Override
    public String toString() {
        // never triggers decryption; the parsed form's own toString is redacted
        return value != null ? value.toString() : "EncryptedAuthConfig{ciphertext}";
    }
}
