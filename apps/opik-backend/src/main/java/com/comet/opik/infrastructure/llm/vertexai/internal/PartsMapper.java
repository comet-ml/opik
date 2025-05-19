package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.FunctionResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.data.video.Video;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.cloud.vertexai.generativeai.PartMaker.fromMimeTypeAndData;
import static dev.langchain4j.internal.Exceptions.illegalArgument;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.Utils.quoted;
import static dev.langchain4j.internal.Utils.readBytes;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

public class PartsMapper {

    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = new HashMap<>();

    static {
        // see image requirements
        // https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/image-understanding#image-requirements
        EXTENSION_TO_MIME_TYPE.put("avif", "image/avif");
        EXTENSION_TO_MIME_TYPE.put("bmp", "image/bmp");
        EXTENSION_TO_MIME_TYPE.put("gif", "image/gif");
        EXTENSION_TO_MIME_TYPE.put("jpe", "image/jpeg");
        EXTENSION_TO_MIME_TYPE.put("jpeg", "image/jpeg");
        EXTENSION_TO_MIME_TYPE.put("jpg", "image/jpeg");
        EXTENSION_TO_MIME_TYPE.put("png", "image/png");
        EXTENSION_TO_MIME_TYPE.put("svg", "image/svg+xml");
        EXTENSION_TO_MIME_TYPE.put("tif", "image/tiff");
        EXTENSION_TO_MIME_TYPE.put("tiff", "image/tiff");
        EXTENSION_TO_MIME_TYPE.put("webp", "image/webp");

        // see audio requirements
        // https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/audio-understanding
        EXTENSION_TO_MIME_TYPE.put("mp3", "audio/mp3");
        EXTENSION_TO_MIME_TYPE.put("wav", "audio/wav");
        EXTENSION_TO_MIME_TYPE.put("aac", "audio/aac");
        EXTENSION_TO_MIME_TYPE.put("flac", "audio/flac");
        EXTENSION_TO_MIME_TYPE.put("mpa", "audio/m4a");
        EXTENSION_TO_MIME_TYPE.put("mpga", "audio/mpga");
        EXTENSION_TO_MIME_TYPE.put("opus", "audio/opus");
        EXTENSION_TO_MIME_TYPE.put("pcm", "audio/pcm");

        // see video requirements:
        // https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/video-understanding
        EXTENSION_TO_MIME_TYPE.put("mp4", "video/mp4");
        EXTENSION_TO_MIME_TYPE.put("mpeg", "video/mpeg");
        EXTENSION_TO_MIME_TYPE.put("mpg", "video/mpg");
        EXTENSION_TO_MIME_TYPE.put("mpegps", "video/mpegps");
        EXTENSION_TO_MIME_TYPE.put("mov", "video/mov");
        EXTENSION_TO_MIME_TYPE.put("avi", "video/avi");
        EXTENSION_TO_MIME_TYPE.put("flv", "video/x-flv");
        EXTENSION_TO_MIME_TYPE.put("webm", "video/webm");
        EXTENSION_TO_MIME_TYPE.put("mmv", "video/wmv");
        EXTENSION_TO_MIME_TYPE.put("3gpp", "video/3gpp");

        // see document understanding:
        // https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/document-understanding
        EXTENSION_TO_MIME_TYPE.put("pdf", "application/pdf");
    }

    static List<Part> map(ChatMessage message) {
        if (message instanceof AiMessage) {
            AiMessage aiMessage = (AiMessage) message;

            List<Part> parts = new ArrayList<>();

            if (aiMessage.text() != null && !aiMessage.text().isEmpty()) {
                parts.add(Part.newBuilder()
                        .setText(aiMessage.text())
                        .build());
            }

            if (aiMessage.hasToolExecutionRequests()) {
                List<Part> fnCallReqParts = aiMessage.toolExecutionRequests().stream()
                        .map(FunctionCallHelper::fromToolExecutionRequest)
                        .map(fnCall -> Part.newBuilder()
                                .setFunctionCall(fnCall)
                                .build())
                        .collect(toList());

                parts.addAll(fnCallReqParts);
            }

            return parts;
        } else if (message instanceof UserMessage) {
            return ((UserMessage) message).contents().stream()
                    .map(PartsMapper::map)
                    .collect(toList());
        } else if (message instanceof SystemMessage) {
            return singletonList(Part.newBuilder()
                    .setText(((SystemMessage) message).text())
                    .build());
        } else if (message instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage toolExecutionResultMessage = (ToolExecutionResultMessage) message;
            String functionResponseText = toolExecutionResultMessage.text();

            Struct.Builder structBuilder = Struct.newBuilder();
            try {
                JsonFormat.parser().merge(functionResponseText, structBuilder);
            } catch (InvalidProtocolBufferException e) {
                // TODO do a proper fix
                String functionResponseTextAsMap = "{\"result\":" + functionResponseText + "}";
                try {
                    JsonFormat.parser().merge(functionResponseTextAsMap, structBuilder);
                } catch (InvalidProtocolBufferException e2) {
                    String functionResponseTextWithQuotesAsMap = "{\"result\":" + quoted(functionResponseText) + "}";
                    try {
                        JsonFormat.parser().merge(functionResponseTextWithQuotesAsMap, structBuilder);
                    } catch (InvalidProtocolBufferException e3) {
                        throw new RuntimeException(e3);
                    }
                }
            }
            Struct responseStruct = structBuilder.build();

            return singletonList(Part.newBuilder()
                    .setFunctionResponse(FunctionResponse.newBuilder()
                            .setName(toolExecutionResultMessage.toolName())
                            .setResponse(responseStruct)
                            .build())
                    .build());
        } else {
            throw illegalArgument(message.type() + " message is not supported by Gemini");
        }
    }

    private static Part map(Content content) {
        if (content instanceof TextContent) {
            return map((TextContent) content);
        } else if (content instanceof ImageContent) {
            return map((ImageContent) content);
        } else if (content instanceof AudioContent) {
            return map((AudioContent) content);
        } else if (content instanceof VideoContent) {
            return map((VideoContent) content);
        } else if (content instanceof PdfFileContent) {
            return map((PdfFileContent) content);
        } else {
            throw illegalArgument("Unknown content type: " + content);
        }
    }

    private static Part map(TextContent content) {
        return Part.newBuilder()
                .setText(content.text())
                .build();
    }

    static Part map(ImageContent content) {
        Image image = content.image();
        return getPart(image.url(), image.mimeType(), image.base64Data());
    }

    static Part map(AudioContent content) {
        Audio audio = content.audio();
        return getPart(audio.url(), audio.mimeType(), audio.base64Data());
    }

    static Part map(VideoContent content) {
        Video video = content.video();
        return getPart(video.url(), video.mimeType(), video.base64Data());
    }

    static Part map(PdfFileContent content) {
        PdfFile pdfFile = content.pdfFile();
        return getPart(pdfFile.url(), pdfFile.mimeType(), pdfFile.base64Data());
    }

    private static Part getPart(URI url, String mimeType, String base64data) {
        if (url != null) {
            String effectiveMimeType = getOrDefault(mimeType, () -> detectMimeType(url));
            if (url.getScheme().equals("gs")) {
                return fromMimeTypeAndData(effectiveMimeType, url);
            } else {
                return fromMimeTypeAndData(effectiveMimeType, readBytes(url.toString()));
            }
        }
        return fromMimeTypeAndData(mimeType, Base64.getDecoder().decode(base64data));
    }

    static String detectMimeType(URI url) {
        String[] pathParts = url.getPath().split("\\.");
        if (pathParts.length > 1) {
            String extension = pathParts[pathParts.length - 1].toLowerCase();
            String mimeType = EXTENSION_TO_MIME_TYPE.get(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }
        throw illegalArgument("Unable to detect the MIME type of '%s'. Please provide it explicitly.", url);
    }
}
