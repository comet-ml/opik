package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Loads a trace/span media attachment (image, audio, video) so the judge can
 * reason over its actual content while scoring (OPIK-6555).
 *
 * <p>Args: {@code {type, id, file_name}} (all required) — copied verbatim from a single
 * {@code attachments} entry, which is self-describing ({@code type}, {@code id}, {@code file_name}
 * together identify the owner). Do not infer the owner from ids elsewhere in the structure.
 * <ul>
 *   <li>{@code type} ∈ {@code trace, span} — the {@code type} shown in that {@code attachments} entry.</li>
 *   <li>{@code id} the {@code id} shown in that same entry (the entity that owns the file).</li>
 *   <li>{@code file_name} the {@code file_name} shown in that same entry.</li>
 * </ul>
 *
 * <p>Fetch does not return the media inline as text — that is impossible in a tool result.
 * Instead it stages the media on {@link TraceToolContext}; {@link ToolCallLoop} drains it
 * after the tool round and appends it as a multimodal {@code UserMessage}. MinIO attachments
 * are inlined as Base64; S3 attachments are passed as a presigned URL.
 *
 * <p>Errors are emitted as {@code {"error": "..."}} JSON strings (never failing the Mono),
 * keeping the judge's tool-call loop alive.
 */
@Singleton
@Slf4j
public class GetAttachmentTool implements ToolExecutor {

    public static final String NAME = "get_attachment";

    private static final Tika TIKA = new Tika();

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("""
                    Load a media attachment (image, audio, or video) belonging to a trace or span \
                    so you can inspect its actual content while scoring. The trace/span structure and \
                    the read tool list attachments in an `attachments` field where each entry is \
                    self-describing: it carries `type`, `id` and `file_name`. Copy those three values \
                    verbatim from one entry into this tool's arguments — do not infer the owner from \
                    other ids in the structure (e.g. a span's nested trace_id). Only image, audio, and \
                    video files can be loaded. Loaded media links are time-limited; if a link has \
                    expired, call this tool again with the same arguments to obtain a fresh one.""")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("type", """
                            The `type` shown in the chosen `attachments` entry (trace or span) — the entity \
                            that owns the file. Copy it verbatim; do not infer it.""")
                    .addStringProperty("id", """
                            The `id` shown in that same `attachments` entry (the owning entity's UUID). \
                            Copy it verbatim.""")
                    .addStringProperty("file_name", """
                            The `file_name` shown in that same `attachments` entry \
                            (e.g. input-attachment-1-1700000000000.png).""")
                    .required("type", "id", "file_name")
                    .build())
            .build();

    private final AttachmentService attachmentService;
    private final OpikConfiguration config;

    @Inject
    public GetAttachmentTool(@NonNull AttachmentService attachmentService, @NonNull OpikConfiguration config) {
        this.attachmentService = attachmentService;
        this.config = config;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolSpecification spec() {
        return SPEC;
    }

    @Override
    public Mono<String> execute(String arguments, @NonNull TraceToolContext ctx) {
        ParsedArgs args = parseArgs(arguments);
        if (args.error != null) {
            return Mono.just(args.error);
        }

        // The evaluation runs within a single project, so the container/project id is the session's
        // projectId (carried on the context) — no need to fetch the trace/span just to learn it.
        UUID projectId = ctx.getProjectId();
        if (projectId == null) {
            return Mono.just(ToolArgs.errorJson("Project context is unavailable; cannot look up attachments."));
        }

        return Mono.defer(() -> {
            UUID entityId = parseUuid(args.id);
            var attachmentEntityType = toAttachmentEntityType(args.type);
            return withRequestContext(
                    attachmentService.getAttachmentInfoByEntity(entityId, attachmentEntityType, projectId), ctx)
                    .flatMap(attachments -> fetchOne(attachments, args, ctx));
        })
                .onErrorResume(NotFoundLikeException.class, e -> Mono.just(ToolArgs.errorJson(e.getMessage())))
                .onErrorResume(Exception.class, e -> {
                    // Don't echo the raw exception to the LLM — surface a correlation id only.
                    String correlationId = UUID.randomUUID().toString();
                    log.warn(
                            "get_attachment tool failed for ref ('{}', '{}'), projectId='{}', workspaceId='{}', correlationId='{}'",
                            args.type, args.id, projectId, ctx.getWorkspaceId(), correlationId, e);
                    return Mono.just(ToolArgs.errorJson(
                            "Failed to load attachment (ref: " + correlationId + ")"));
                });
    }

    // ---------------- Fetch ----------------

    private Mono<String> fetchOne(List<AttachmentInfo> attachments, ParsedArgs args, TraceToolContext ctx) {
        AttachmentInfo info = attachments.stream()
                .filter(a -> args.fileName.equals(a.fileName()))
                .findFirst()
                .orElse(null);
        if (info == null) {
            return Mono.just(ToolArgs.errorJson(("No attachment named '%s' on this %s. Use the file names from"
                    + " the entity's `attachments` list (from reading the %s).")
                    .formatted(args.fileName, args.type.name().toLowerCase(), args.type.name().toLowerCase())));
        }

        String mime = effectiveMimeType(info);
        MediaCategory category = MediaCategory.fromMimeType(mime);
        if (!category.isViewable()) {
            return Mono.just(ToolArgs.errorJson(("Attachment '%s' is %s, which is not a viewable image, audio,"
                    + " or video file and cannot be loaded.").formatted(args.fileName, mime)));
        }

        if (info.fileSize() == 0) {
            return Mono
                    .just(ToolArgs.errorJson(("Attachment '%s' cannot be loaded because its reported file size is 0.")
                            .formatted(args.fileName)));
        }

        if (config.getS3Config().isMinIO()) {
            return fetchFromMinIO(info, mime, category, ctx);
        }
        return fetchFromS3(info, mime, category, ctx);
    }

    private Mono<String> fetchFromMinIO(AttachmentInfo info, String mime, MediaCategory category,
            TraceToolContext ctx) {
        // Base64 encoding inflates by ~4/3 — pre-check with the inflated estimate to avoid downloading a
        // file that would overflow the cap anyway. The check uses ceiling arithmetic: (n*4+2)/3.
        long base64EstimatedSize = (info.fileSize() * 4 + 2) / 3;
        if (!ctx.canInjectMedia(base64EstimatedSize)) {
            return Mono.just(ToolArgs.errorJson(("Attachment '%s' cannot be loaded: the total size limit for"
                    + " injected attachments in this evaluation has been reached.").formatted(info.fileName())));
        }
        return Mono.fromCallable(() -> attachmentService.downloadAttachment(info, ctx.getWorkspaceId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::readAllBytes)
                .map(bytes -> {
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    // Stage with the actual base64 length so the accumulator reflects what is injected.
                    ctx.stageMedia(MediaPayload.ofBase64(info.fileName(), mime, category, base64.length(), base64));
                    return confirmation(info.fileName(), category);
                });
    }

    private Mono<String> fetchFromS3(AttachmentInfo info, String mime, MediaCategory category,
            TraceToolContext ctx) {
        if (!ctx.canInjectMedia(info.fileSize())) {
            return Mono.just(ToolArgs.errorJson(("Attachment '%s' cannot be loaded: the total size limit for"
                    + " injected attachments in this evaluation has been reached.").formatted(info.fileName())));
        }
        return Mono.fromCallable(() -> {
            Duration ttl = Duration.ofSeconds(config.getOnlineScoring().getAgenticToolsS3PresignTtlSeconds());
            String url = attachmentService.presignDownloadUrl(info, ctx.getWorkspaceId(), ttl);
            ctx.stageMedia(MediaPayload.ofUrl(info.fileName(), mime, category, info.fileSize(), url));
            return confirmation(info.fileName(), category);
        });
    }

    private String confirmation(String fileName, MediaCategory category) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("loaded", true);
        node.put("file_name", fileName);
        node.put("media_type", category.name().toLowerCase());
        node.put("note", "The attachment is provided as viewable media in the next message.");
        return node.toString();
    }

    // ---------------- Helpers ----------------

    private Mono<byte[]> readAllBytes(InputStream inputStream) {
        return Mono.fromCallable(() -> {
            try {
                return IOUtils.toByteArray(inputStream);
            } finally {
                inputStream.close();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String effectiveMimeType(AttachmentInfo info) {
        if (StringUtils.isNotBlank(info.mimeType())) {
            return info.mimeType();
        }
        return TIKA.detect(info.fileName());
    }

    private static <T> Mono<T> withRequestContext(Mono<T> mono, TraceToolContext ctx) {
        return mono.contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                .put(RequestContext.USER_NAME, ctx.getUserName()));
    }

    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundLikeException("Invalid id format: " + id);
        }
    }

    private static com.comet.opik.api.attachment.EntityType toAttachmentEntityType(EntityType type) {
        return switch (type) {
            case TRACE -> com.comet.opik.api.attachment.EntityType.TRACE;
            case SPAN -> com.comet.opik.api.attachment.EntityType.SPAN;
            default -> throw new NotFoundLikeException("Unsupported type: " + type);
        };
    }

    // ---------------- Argument parsing ----------------

    private static ParsedArgs parseArgs(String arguments) {
        if (arguments == null) {
            return ParsedArgs.error(ToolArgs.errorJson("Missing arguments"));
        }
        try {
            JsonNode node = JsonUtils.getJsonNodeFromString(arguments);
            if (node == null || !node.isObject()) {
                return ParsedArgs.error(ToolArgs.errorJson("Arguments must be a JSON object"));
            }
            var typeRes = ToolArgs.parseType(node, NAME);
            if (typeRes.isError()) {
                return ParsedArgs.error(typeRes.error());
            }
            EntityType type = typeRes.value();
            if (type != EntityType.TRACE && type != EntityType.SPAN) {
                return ParsedArgs.error(ToolArgs.errorJson(
                        "get_attachment supports only type=trace or type=span; got: " + type.name().toLowerCase()));
            }
            var idRes = ToolArgs.requireString(node, "id");
            if (idRes.isError()) {
                return ParsedArgs.error(idRes.error());
            }
            var fileNameRes = ToolArgs.requireString(node, "file_name");
            if (fileNameRes.isError()) {
                return ParsedArgs.error(fileNameRes.error());
            }
            return ParsedArgs.builder()
                    .type(type)
                    .id(idRes.value())
                    .fileName(fileNameRes.value())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse get_attachment tool arguments: '{}'", arguments, e);
            return ParsedArgs.error(ToolArgs.errorJson("Malformed arguments: " + e.getMessage()));
        }
    }

    @Builder(toBuilder = true)
    private record ParsedArgs(EntityType type, String id, String fileName, String error) {
        static ParsedArgs error(String err) {
            return ParsedArgs.builder().error(err).build();
        }
    }

    /** Internal sentinel converted to a {@code {"error": ...}} response by {@link #execute}. */
    private static final class NotFoundLikeException extends RuntimeException {
        NotFoundLikeException(String message) {
            super(message);
        }
    }
}
