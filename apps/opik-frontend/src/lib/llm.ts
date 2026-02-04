import {
  AudioPart,
  ImagePart,
  VideoPart,
  LLM_MESSAGE_ROLE,
  LLMMessage,
  MessageContent,
  TextPart,
} from "@/types/llm";
import { generateRandomString } from "@/lib/utils";

export const generateDefaultLLMPromptMessage = (
  message: Partial<LLMMessage> = {},
): LLMMessage => {
  return {
    content: "",
    role: LLM_MESSAGE_ROLE.user,
    ...message,
    id: generateRandomString(),
  };
};

export const getNextMessageType = (
  previousMessage: LLMMessage,
): LLM_MESSAGE_ROLE => {
  if (previousMessage.role === LLM_MESSAGE_ROLE.user) {
    return LLM_MESSAGE_ROLE.assistant;
  }

  return LLM_MESSAGE_ROLE.user;
};

export const getTextFromMessageContent = (content: MessageContent): string => {
  if (typeof content === "string") {
    return content;
  }

  return content.find((c): c is TextPart => c.type === "text")?.text || "";
};

export const appendTextToMessageContent = (
  content: MessageContent,
  textToAppend: string,
): MessageContent => {
  if (typeof content === "string") {
    return content + textToAppend;
  }

  const lastTextPartIndex = content.findLastIndex(
    (part): part is TextPart => part.type === "text",
  );

  if (lastTextPartIndex !== -1) {
    return content.map((part, index) =>
      index === lastTextPartIndex && part.type === "text"
        ? { ...part, text: part.text + textToAppend }
        : part,
    );
  }

  return [...content, { type: "text" as const, text: textToAppend }];
};

export const getImagesFromMessageContent = (
  content: MessageContent,
): string[] => {
  if (typeof content === "string") {
    return [];
  }

  return content
    .filter((c): c is ImagePart => c.type === "image_url")
    .map((c) => c.image_url.url);
};

export const getVideosFromMessageContent = (
  content: MessageContent,
): string[] => {
  if (typeof content === "string") {
    return [];
  }

  return content
    .filter((c): c is VideoPart => c.type === "video_url")
    .map((c) => c.video_url.url);
};

export const getAudiosFromMessageContent = (
  content: MessageContent,
): string[] => {
  if (typeof content === "string") {
    return [];
  }

  return content
    .filter((c): c is AudioPart => c.type === "audio_url")
    .map((c) => c.audio_url.url);
};

export const hasImagesInContent = (content: MessageContent): boolean => {
  if (typeof content === "string") {
    return false;
  }

  return content.some((c): c is ImagePart => c.type === "image_url");
};

export const isMediaAllowedForRole = (
  messageRole: LLM_MESSAGE_ROLE,
): boolean => {
  return messageRole === LLM_MESSAGE_ROLE.user;
};

export const hasVideosInContent = (
  content: MessageContent,
): content is Array<TextPart | ImagePart | VideoPart | AudioPart> => {
  if (typeof content === "string") return false;

  return content.some((c): c is VideoPart => c.type === "video_url");
};

export const hasAudiosInContent = (
  content: MessageContent,
): content is Array<TextPart | ImagePart | VideoPart | AudioPart> => {
  if (typeof content === "string") return false;

  return content.some((c): c is AudioPart => c.type === "audio_url");
};

/**
 * Get all template strings from message content (text, image URLs, video URLs, and audio URLs)
 * Used for extracting mustache variables from all parts of a message
 */
export const getAllTemplateStringsFromContent = (
  content: MessageContent,
): string[] => {
  if (typeof content === "string") {
    return [content];
  }

  return content.map((part) => {
    if (part.type === "text") {
      return part.text;
    } else if (part.type === "image_url") {
      return part.image_url.url;
    } else if (part.type === "video_url") {
      return part.video_url.url;
    } else {
      return part.audio_url.url;
    }
  });
};

export const parseLLMMessageContent = (
  content: MessageContent,
): { text: string; images: string[]; videos: string[]; audios: string[] } => {
  return {
    text: getTextFromMessageContent(content),
    images: getImagesFromMessageContent(content),
    videos: getVideosFromMessageContent(content),
    audios: getAudiosFromMessageContent(content),
  };
};

/**
 * Convert a single LLMMessage to messages_json format (array of messages with role and content)
 * This format is used when saving playground prompts to maintain full structure including images
 */
export const convertMessageToMessagesJson = (message: LLMMessage): string => {
  const messageJson: Array<{
    role: string;
    content: MessageContent;
  }> = [
    {
      role: message.role,
      content: message.content,
    },
  ];

  return JSON.stringify(messageJson, null, 2);
};

/**
 * Parse messages_json template back to MessageContent
 * Extracts the content from the first message in the array
 * Returns empty string if parsing fails or format is invalid
 */
export const parseMessagesJsonToContent = (
  template: string,
): MessageContent => {
  try {
    const parsed = JSON.parse(template);

    // Check if it's an array with at least one message
    if (Array.isArray(parsed) && parsed.length > 0) {
      const firstMessage = parsed[0];

      // Validate message structure
      if (
        firstMessage &&
        typeof firstMessage === "object" &&
        "content" in firstMessage
      ) {
        return firstMessage.content as MessageContent;
      }
    }

    // If parsing fails or format is invalid, return empty string
    return "";
  } catch {
    // If JSON parsing fails, return empty string
    return "";
  }
};

/**
 * Type guard to check if metadata indicates a messages_json format prompt
 * Messages_json format is used for prompts created/saved from the Opik UI playground
 */
export const isMessagesJsonFormat = (
  metadata?: object,
): metadata is { created_from: "opik_ui"; type: "messages_json" } => {
  if (!metadata || typeof metadata !== "object") return false;
  const meta = metadata as Record<string, unknown>;
  return meta.created_from === "opik_ui" && meta.type === "messages_json";
};

/**
 * Parse prompt version content from a prompt object
 * Extracts template and metadata from a prompt version and parses based on format:
 * - If metadata indicates messages_json format, parses as structured message content
 * - Otherwise treats as plain text for backward compatibility
 */
export const parsePromptVersionContent = (promptVersion?: {
  template?: string;
  metadata?: object;
}): MessageContent => {
  const template = promptVersion?.template ?? "";
  const metadata = promptVersion?.metadata;

  if (isMessagesJsonFormat(metadata)) {
    return parseMessagesJsonToContent(template);
  }
  // Backward compatibility: treat as plain text
  return template;
};

export const extractDisplayMessages = (
  messages?: Array<{ role: string; content: string }>,
): Array<{ role: string; content: string }> | undefined => {
  return messages?.map((msg) => ({
    role: msg.role,
    content: msg.content,
  }));
};

/**
 * Parse chat template JSON string into LLMMessage array
 * Factory method that converts chat template to LLMMessage format with optional metadata
 *
 * @param template - JSON string containing array of messages with role and content
 * @param options - Optional configuration for parsed messages
 * @param options.promptId - Prompt ID to attach to each message
 * @param options.promptVersionId - Prompt version ID to attach to each message
 * @param options.useTimestamp - Whether to include timestamp in message IDs for uniqueness (default: false)
 * @returns Array of LLMMessage objects, or empty array if parsing fails
 *
 * Note: Content is kept as-is (string or array format) as per MessageContent type definition
 */

export const parseChatTemplateToLLMMessages = (
  template: string,
  options: {
    promptId?: string;
    promptVersionId?: string;
    useTimestamp?: boolean;
  } = {},
): LLMMessage[] => {
  const { promptId, promptVersionId, useTimestamp = false } = options;

  try {
    const parsed = JSON.parse(template);

    if (!Array.isArray(parsed) || parsed.length === 0) {
      return [];
    }

    return parsed.map((msg, index) => {
      // Generate ID with optional timestamp for uniqueness
      const id = useTimestamp ? `msg-${index}-${Date.now()}` : `msg-${index}`;

      return {
        id,
        role: msg.role as LLM_MESSAGE_ROLE,
        content: msg.content, // Keep as-is: string | Array<TextPart | ImagePart | VideoPart>
        ...(promptId && { promptId }),
        ...(promptVersionId && { promptVersionId }),
      };
    });
  } catch {
    return [];
  }
};
