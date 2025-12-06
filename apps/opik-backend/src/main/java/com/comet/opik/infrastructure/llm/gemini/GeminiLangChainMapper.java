package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.video.Video;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Message;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Gemini-specific mapper for handling multimodal content.
 * Downloads video content and sends as base64 inline data for small files,
 * or uploads to Google's File API for large files (>20MB).
 */
@Mapper
public interface GeminiLangChainMapper {

    Duration VIDEO_DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    Duration FILE_UPLOAD_TIMEOUT = Duration.ofMinutes(5);
    long MAX_INLINE_SIZE_BYTES = 20 * 1024 * 1024; // 20MB limit for inline data
    String GEMINI_FILE_API_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files";
    String GEMINI_FILE_STATUS_URL = "https://generativelanguage.googleapis.com/v1beta/";
    Duration FILE_POLL_INTERVAL = Duration.ofSeconds(2);
    int MAX_POLL_ATTEMPTS = 30; // Max 60 seconds of polling
    String DEFAULT_VIDEO_MIME_TYPE = "video/mp4";
    ObjectMapper JSON_MAPPER = new ObjectMapper();

    GeminiLangChainMapper INSTANCE = Mappers.getMapper(GeminiLangChainMapper.class);

    /**
     * Map messages for Gemini with proper MIME type handling.
     * @param request The chat completion request
     * @param apiKey The Gemini API key (needed for File API uploads)
     */
    default List<ChatMessage> mapMessagesForGemini(@NonNull ChatCompletionRequest request, @NonNull String apiKey) {
        return request.messages().stream()
                .map(message -> toChatMessageForGemini(message, apiKey))
                .toList();
    }

    /**
     * Legacy method without API key - uses inline base64 only (will fail for large files).
     */
    default List<ChatMessage> mapMessagesForGemini(@NonNull ChatCompletionRequest request) {
        return mapMessagesForGemini(request, null);
    }

    /**
     * Convert Message to ChatMessage, with Gemini-specific handling for OpikUserMessage.
     */
    default ChatMessage toChatMessageForGemini(@NonNull Message message, String apiKey) {
        if (message instanceof OpikUserMessage opikUserMessage) {
            return convertOpikUserMessageForGemini(opikUserMessage, apiKey);
        }
        return LlmProviderLangChainMapper.INSTANCE.toChatMessage(message);
    }

    /**
     * Convert OpikUserMessage to UserMessage for Gemini.
     */
    default UserMessage convertOpikUserMessageForGemini(OpikUserMessage opikUserMessage, String apiKey) {
        if (opikUserMessage.content() instanceof String stringContent) {
            if (stringContent == null || stringContent.isBlank()) {
                throw new BadRequestException("Message content cannot be null or empty");
            }
            return UserMessage.from(stringContent);
        } else if (opikUserMessage.content() instanceof List<?> contentList) {
            List<Content> publicApiContents = new ArrayList<>();
            for (Object item : contentList) {
                if (item instanceof OpikContent opikContent) {
                    publicApiContents.add(convertOpikContentForGemini(opikContent, apiKey));
                }
            }
            return UserMessage.from(publicApiContents);
        }
        throw new BadRequestException("Invalid OpikUserMessage content type");
    }

    /**
     * Convert OpikContent to public API Content for Gemini.
     * For videos: downloads and uses inline base64 for small files, or File API for large files.
     */
    default Content convertOpikContentForGemini(OpikContent opikContent, String apiKey) {
        return switch (opikContent.type()) {
            case TEXT -> TextContent.from(opikContent.text());
            case IMAGE_URL -> {
                if (opikContent.imageUrl() != null) {
                    yield ImageContent.from(opikContent.imageUrl().getUrl());
                }
                throw new BadRequestException("Image URL is null");
            }
            case VIDEO_URL -> {
                if (opikContent.videoUrl() != null) {
                    yield processVideoUrl(opikContent.videoUrl().url(), apiKey);
                }
                throw new BadRequestException("Video URL is null");
            }
            case AUDIO -> throw new BadRequestException("Audio content not yet supported for Gemini");
            case FILE -> throw new BadRequestException("File content not yet supported for Gemini");
        };
    }

    /**
     * Process video URL: download and either use inline base64 or upload to File API.
     */
    private VideoContent processVideoUrl(String videoUrl, String apiKey) {
        byte[] videoBytes = downloadVideo(videoUrl);

        if (videoBytes.length < MAX_INLINE_SIZE_BYTES) {
            // Small file: use inline base64
            String base64Data = Base64.getEncoder().encodeToString(videoBytes);
            Video video = Video.builder()
                    .base64Data(base64Data)
                    .mimeType(DEFAULT_VIDEO_MIME_TYPE)
                    .build();
            return new VideoContent(video);
        } else {
            // Large file: upload to Google's File API
            if (apiKey == null || apiKey.isBlank()) {
                throw new BadRequestException(
                        "Video file is too large for inline data (>" + (MAX_INLINE_SIZE_BYTES / 1024 / 1024)
                                + "MB). API key required for File API upload.");
            }
            String fileUri = uploadToGeminiFileApi(videoBytes, apiKey);
            Video video = Video.builder()
                    .url(fileUri)
                    .mimeType(DEFAULT_VIDEO_MIME_TYPE)
                    .build();
            return new VideoContent(video);
        }
    }

    /**
     * Downloads video from URL and returns the bytes.
     */
    private byte[] downloadVideo(String videoUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(VIDEO_DOWNLOAD_TIMEOUT)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(videoUrl))
                    .timeout(VIDEO_DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new InternalServerErrorException(
                        "Failed to download video from URL: HTTP " + response.statusCode());
            }

            return response.body();

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalServerErrorException("Failed to download video from URL: " + e.getMessage(), e);
        }
    }

    /**
     * Uploads video to Google's File API using resumable upload protocol.
     * Returns the file URI that can be used in content requests.
     */
    private String uploadToGeminiFileApi(byte[] videoBytes, String apiKey) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(FILE_UPLOAD_TIMEOUT)
                    .build();

            // Step 1: Start resumable upload to get upload URL
            String startUploadBody = "{\"file\": {\"display_name\": \"video_upload\"}}";

            HttpRequest startRequest = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_FILE_API_URL + "?key=" + apiKey))
                    .timeout(FILE_UPLOAD_TIMEOUT)
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", String.valueOf(videoBytes.length))
                    .header("X-Goog-Upload-Header-Content-Type", DEFAULT_VIDEO_MIME_TYPE)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(startUploadBody))
                    .build();

            HttpResponse<String> startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString());

            if (startResponse.statusCode() != 200) {
                throw new InternalServerErrorException(
                        "Failed to start file upload: HTTP " + startResponse.statusCode() + " - "
                                + startResponse.body());
            }

            // Get upload URL from response headers
            String uploadUrl = startResponse.headers()
                    .firstValue("x-goog-upload-url")
                    .orElseThrow(() -> new InternalServerErrorException("No upload URL in response"));

            // Step 2: Upload the video bytes
            // Note: Content-Length is automatically set by HttpClient
            HttpRequest uploadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(FILE_UPLOAD_TIMEOUT)
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(videoBytes))
                    .build();

            HttpResponse<String> uploadResponse = client.send(uploadRequest, HttpResponse.BodyHandlers.ofString());

            if (uploadResponse.statusCode() != 200) {
                throw new InternalServerErrorException(
                        "Failed to upload video: HTTP " + uploadResponse.statusCode() + " - " + uploadResponse.body());
            }

            // Step 3: Parse response to get file name and URI
            JsonNode responseJson = JSON_MAPPER.readTree(uploadResponse.body());
            String fileName = responseJson.path("file").path("name").asText();
            String fileUri = responseJson.path("file").path("uri").asText();

            if (fileName == null || fileName.isBlank()) {
                throw new InternalServerErrorException("No file name in upload response: " + uploadResponse.body());
            }

            // Step 4: Poll until file is ACTIVE
            String fileState = responseJson.path("file").path("state").asText();
            int pollAttempts = 0;

            while ("PROCESSING".equals(fileState) && pollAttempts < MAX_POLL_ATTEMPTS) {
                try {
                    Thread.sleep(FILE_POLL_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new InternalServerErrorException("Interrupted while waiting for file processing");
                }

                // Check file status
                HttpRequest statusRequest = HttpRequest.newBuilder()
                        .uri(URI.create(GEMINI_FILE_STATUS_URL + fileName + "?key=" + apiKey))
                        .timeout(FILE_UPLOAD_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> statusResponse = client.send(statusRequest, HttpResponse.BodyHandlers.ofString());

                if (statusResponse.statusCode() != 200) {
                    throw new InternalServerErrorException(
                            "Failed to check file status: HTTP " + statusResponse.statusCode());
                }

                JsonNode statusJson = JSON_MAPPER.readTree(statusResponse.body());
                fileState = statusJson.path("state").asText();
                fileUri = statusJson.path("uri").asText();
                pollAttempts++;
            }

            if ("PROCESSING".equals(fileState)) {
                throw new InternalServerErrorException("File processing timed out after "
                        + (MAX_POLL_ATTEMPTS * FILE_POLL_INTERVAL.toSeconds()) + " seconds");
            }

            if ("FAILED".equals(fileState)) {
                throw new InternalServerErrorException("File processing failed");
            }

            if (fileUri == null || fileUri.isBlank()) {
                throw new InternalServerErrorException("No file URI after processing");
            }

            return fileUri;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalServerErrorException("Failed to upload video to File API: " + e.getMessage(), e);
        }
    }
}
