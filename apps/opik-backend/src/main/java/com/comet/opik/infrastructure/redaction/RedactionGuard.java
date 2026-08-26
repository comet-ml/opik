package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses a response the serializer cannot reach.
 * <p>
 * {@code COVERED_PATHS} declares the whole private API in scope, and the request filter duly resolves a decision
 * for every request under it. Three responses under that scope are not written by Jackson, so the decision is
 * resolved and then has nothing to act on:
 * <ul>
 * <li>attachment download, which streams bytes that never become a {@code JsonNode};</li>
 * <li>Agent Insights free-form SQL, where rewriting the result is not enforceable in principle - the caller
 * chooses the projection, so a value returned through {@code base64()} or {@code substring()} matches no rule
 * written against the plain text.</li>
 * </ul>
 * These are all authenticated and workspace-scoped already; that is a different question. The permission this
 * gates on is {@code trace_original_data_view}, which an authenticated member in good standing may lack, and who
 * would receive masked content from the JSON API for the same data.
 * <p>
 * So refusing is the honest answer rather than an omission: withholding is the point of the feature, and a
 * response that cannot be masked cannot be withheld any other way. It applies only while the feature is enabled
 * and the caller lacks the permission, so an install with the flag off is unaffected.
 * <p>
 * TODO: the dataset CSV export is a third such response and is deliberately <em>not</em> handled. Its file is
 * produced by a background consumer with no caller, so the decision belongs to the download, which does have one:
 * a permitted caller should receive the stored bytes and an unpermitted one the same rows with the rules applied
 * per cell, through the CSV parser so the rules cannot reach the delimiters. Left out because
 * {@code datasetExport.enabled} defaults to false, so the path is unreachable on a default deployment - it needs
 * doing before the export feature is switched on anywhere, not before this merges.
 */
@Slf4j
@UtilityClass
public class RedactionGuard {

    /**
     * @param redactResponse the decision already resolved for this caller by {@code RedactionRequestFilter}
     * @param what           named in the refusal, so the caller learns which response was withheld
     * @throws ForbiddenException when this caller's responses are masked
     */
    public static void rejectUnmaskable(boolean redactResponse, @NonNull String what) {
        if (!redactResponse) {
            return;
        }

        log.info("Refusing '{}': the response cannot be masked and the caller lacks '{}'", what,
                WorkspaceUserPermission.TRACE_ORIGINAL_DATA_VIEW.getValue());

        throw new ForbiddenException(
                "%s returns stored content that cannot be masked. The '%s' permission is required to read it."
                        .formatted(what, WorkspaceUserPermission.TRACE_ORIGINAL_DATA_VIEW.getValue()));
    }
}
