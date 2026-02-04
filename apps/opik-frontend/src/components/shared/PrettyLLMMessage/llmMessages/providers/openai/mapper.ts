import PrettyLLMMessage from "@/components/shared/PrettyLLMMessage";
import {
  ProviderMapper,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
  LLMMapperResult,
} from "../../types";
import { MessageRole } from "@/components/shared/PrettyLLMMessage/types";
import { isPlaceholder } from "../../utils";

/**
 * OpenAI message content item types
 */
interface OpenAITextContent {
  type: "text";
  text: string;
}

interface OpenAIImageContent {
  type: "image_url";
  image_url: {
    url: string;
    detail?: string;
  };
}

interface OpenAIInputAudioContent {
  type: "input_audio";
  input_audio: {
    data: string;
    format: string;
  };
}

type OpenAIContentItem =
  | OpenAITextContent
  | OpenAIImageContent
  | OpenAIInputAudioContent;

interface OpenAIToolCall {
  id: string;
  type: string;
  function: {
    name: string;
    arguments: string;
  };
}

interface OpenAIAudio {
  id: string;
  data: string;
  expires_at?: number;
  transcript?: string;
}

interface OpenAIMessage {
  role: MessageRole;
  content?: string | OpenAIContentItem[] | null;
  tool_calls?: OpenAIToolCall[];
  tool_call_id?: string;
  name?: string;
  refusal?: string | null;
  audio?: OpenAIAudio;
}

interface OpenAICustomInputMessage {
  role: MessageRole;
  text?: string | OpenAIContentItem[] | null;
  files?: unknown[];
  tool_calls?: OpenAIToolCall[];
  tool_call_id?: string;
  name?: string;
}

interface OpenAIChoice {
  message: OpenAIMessage;
  index: number;
  finish_reason?: string;
}

interface OpenAIInputData {
  messages: OpenAIMessage[];
}

interface OpenAIOutputData {
  choices: OpenAIChoice[];
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
}

interface OpenAICustomInputFormat {
  input: OpenAICustomInputMessage[];
}

interface OpenAICustomOutputFormat {
  text: string;
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  finish_reason?: string;
}

type OpenAIDirectArrayInput = OpenAIMessage[];

/**
 * Generates a deterministic ID for a message
 */
const generateMessageId = (index: number, prefix: string = "msg"): string => {
  return `${prefix}-${index}`;
};

/**
 * Extracts the image name from a URL or returns a default
 */
const getImageName = (url: string, index: number): string => {
  if (url.startsWith("data:")) {
    return `Image ${index + 1}`;
  }
  try {
    const urlObj = new URL(url);
    const pathname = urlObj.pathname;
    const filename = pathname.split("/").pop();
    return filename || `Image ${index + 1}`;
  } catch {
    return `Image ${index + 1}`;
  }
};

/**
 * Processes multimodal content array (images, audio, text).
 * Groups consecutive images and audios together, flushing them when text is encountered.
 */
const processMultimodalContent = (
  content: OpenAIContentItem[],
  role: MessageRole,
  blocks: LLMBlockDescriptor[],
): void => {
  const images: Array<{ url: string; name: string }> = [];
  const audios: Array<{ url: string; name: string }> = [];

  content.forEach((item, index) => {
    if (item.type === "text") {
      // If we have pending images, add them first
      if (images.length > 0) {
        blocks.push({
          blockType: "image",
          component: PrettyLLMMessage.ImageBlock,
          props: {
            images: [...images],
          },
        });
        images.length = 0;
      }
      // If we have pending audios, add them first
      if (audios.length > 0) {
        blocks.push({
          blockType: "audio",
          component: PrettyLLMMessage.AudioPlayerBlock,
          props: {
            audios: [...audios],
          },
        });
        audios.length = 0;
      }
      blocks.push({
        blockType: "text",
        component: PrettyLLMMessage.TextBlock,
        props: {
          children: item.text,
          role,
          showMoreButton: true,
        },
      });
    } else if (item.type === "image_url") {
      // Guard against missing or invalid image_url
      if (
        item.image_url &&
        typeof item.image_url === "object" &&
        typeof item.image_url.url === "string" &&
        item.image_url.url.length > 0
      ) {
        const url = item.image_url.url;
        images.push({
          url: url,
          name: isPlaceholder(url) ? url : getImageName(url, index),
        });
      }
      // Skip invalid image entries silently
    } else if (item.type === "input_audio") {
      // Handle input audio content
      if (
        item.input_audio &&
        typeof item.input_audio === "object" &&
        typeof item.input_audio.data === "string" &&
        item.input_audio.data.length > 0
      ) {
        const audioData = item.input_audio.data;
        const audioName = isPlaceholder(audioData)
          ? audioData
          : `Audio ${index + 1}`;
        audios.push({
          url: audioData,
          name: audioName,
        });
      }
      // Skip invalid audio entries silently
    }
  });

  // Add any remaining images
  if (images.length > 0) {
    blocks.push({
      blockType: "image",
      component: PrettyLLMMessage.ImageBlock,
      props: {
        images,
      },
    });
  }

  // Add any remaining audios
  if (audios.length > 0) {
    blocks.push({
      blockType: "audio",
      component: PrettyLLMMessage.AudioPlayerBlock,
      props: {
        audios,
      },
    });
  }
};

/**
 * Maps tool calls to code block descriptors with formatted JSON arguments.
 */
const mapToolCalls = (
  toolCalls: OpenAIToolCall[],
  blocks: LLMBlockDescriptor[],
): void => {
  toolCalls.forEach((toolCall) => {
    // Guard: skip if function is missing or invalid
    if (
      !toolCall.function ||
      typeof toolCall.function !== "object" ||
      !toolCall.function.name ||
      typeof toolCall.function.arguments !== "string"
    ) {
      // Use safe fallback
      blocks.push({
        blockType: "code",
        component: PrettyLLMMessage.CodeBlock,
        props: {
          code: "",
          label: toolCall.function?.name ?? `unknown tool`,
        },
      });
      return;
    }

    let formattedArgs = toolCall.function.arguments;
    try {
      const parsed = JSON.parse(toolCall.function.arguments);
      formattedArgs = JSON.stringify(parsed, null, 2);
    } catch {
      // Keep original if not parseable
    }

    blocks.push({
      blockType: "code",
      component: PrettyLLMMessage.CodeBlock,
      props: {
        code: formattedArgs,
        label: toolCall.function.name,
      },
    });
  });
};

/**
 * Formats content as a pretty-printed JSON code block for tool results.
 */
const formatAsToolResult = (
  content: string | unknown,
  name?: string,
): LLMBlockDescriptor => {
  const contentStr =
    typeof content === "string" ? content : JSON.stringify(content, null, 2);

  let formattedContent = contentStr;
  try {
    const jsonLike = contentStr.replace(/'/g, '"');
    const parsed = JSON.parse(jsonLike);
    formattedContent = JSON.stringify(parsed, null, 2);
  } catch {
    // Keep original if not parseable
  }

  return {
    blockType: "code",
    component: PrettyLLMMessage.CodeBlock,
    props: {
      code: formattedContent,
      label: name || "Tool result",
    },
  };
};

/**
 * Common content mapping configuration
 */
interface ContentMappingConfig {
  content: string | OpenAIContentItem[] | null | undefined;
  role: MessageRole;
  messageRole: MessageRole;
  name?: string;
  toolCalls?: OpenAIToolCall[];
  audio?: OpenAIAudio;
}

/**
 * Builds block descriptors from message content.
 * Shared helper that handles text, multimodal content, tool calls, and tool results.
 *
 * This consolidates the common logic between standard OpenAI messages and custom input messages.
 */
const buildContentBlocks = (
  config: ContentMappingConfig,
): LLMBlockDescriptor[] => {
  const { content, role, messageRole, name, toolCalls, audio } = config;
  const blocks: LLMBlockDescriptor[] = [];

  // Handle string content
  if (typeof content === "string") {
    // For tool role (including normalized legacy "function" role), format as code
    if (role === "tool") {
      blocks.push(formatAsToolResult(content, name));
    } else {
      blocks.push({
        blockType: "text",
        component: PrettyLLMMessage.TextBlock,
        props: {
          children: content,
          role,
          showMoreButton: true,
        },
      });
    }
  }
  // Handle array content (multimodal)
  else if (Array.isArray(content)) {
    processMultimodalContent(content, role, blocks);
  }

  // Handle audio messages (message-level audio, not content-level)
  if (audio) {
    // Add audio player block if data is present
    if (audio.data) {
      blocks.push({
        blockType: "audio",
        component: PrettyLLMMessage.AudioPlayerBlock,
        props: {
          audios: [
            {
              url: audio.data,
              name: audio.id,
            },
          ],
        },
      });
    }

    // Add transcript as text block if present
    if (audio.transcript) {
      blocks.push({
        blockType: "text",
        component: PrettyLLMMessage.TextBlock,
        props: {
          children: audio.transcript,
          role,
          showMoreButton: true,
        },
      });
    }
  }

  // Handle tool calls (assistant requesting tools)
  if (toolCalls && toolCalls.length > 0) {
    mapToolCalls(toolCalls, blocks);
  }

  // Handle tool result messages (for non-string content)
  if (messageRole === "tool" && content && typeof content !== "string") {
    blocks.push(formatAsToolResult(content, name));

    // Remove any text blocks that duplicate the tool content
    return blocks.filter(
      (block) =>
        !(block.blockType === "text" && block.props.children === content),
    );
  }

  return blocks;
};

/**
 * Converts custom input message content to block descriptors.
 * Handles messages with 'text' field instead of 'content'.
 */
const mapCustomInputMessageContent = (
  message: OpenAICustomInputMessage,
  role: MessageRole,
): LLMBlockDescriptor[] => {
  return buildContentBlocks({
    content: message.text,
    role,
    messageRole: message.role,
    name: message.name,
    toolCalls: message.tool_calls,
  });
};

/**
 * Converts OpenAI message content to block descriptors.
 * Note: Placeholders like "[image_0]" are kept as-is in the URL.
 * The ImageBlock component will resolve them using MediaContext.
 */
const mapMessageContent = (
  message: OpenAIMessage,
  role: MessageRole,
): LLMBlockDescriptor[] => {
  return buildContentBlocks({
    content: message.content,
    role,
    messageRole: message.role,
    name: message.name,
    toolCalls: message.tool_calls,
    audio: message.audio,
  });
};

/**
 * Maps a custom input message to our normalized LLMMessageDescriptor structure
 */
const mapCustomInputMessage = (
  message: OpenAICustomInputMessage,
  index: number,
  prefix: string,
): LLMMessageDescriptor => {
  // Normalize legacy "function" role to "tool"
  const rawRole = message.role;
  const role = rawRole === "function" ? "tool" : (rawRole as MessageRole);

  // For tool messages, use the function name as label
  const label = role === "tool" ? message.name : undefined;

  // Map content blocks to descriptors
  const blocks = mapCustomInputMessageContent(message, role);

  return {
    id: generateMessageId(index, prefix),
    role,
    label,
    blocks,
  };
};

/**
 * Maps an OpenAI message to our normalized LLMMessageDescriptor structure
 */
const mapOpenAIMessage = (
  message: OpenAIMessage,
  index: number,
  prefix: string,
): LLMMessageDescriptor => {
  // Normalize legacy "function" role to "tool"
  const rawRole = message.role;
  const role = rawRole === "function" ? "tool" : (rawRole as MessageRole);

  // For tool messages, use the function name as label
  const label = role === "tool" ? message.name : undefined;

  // Map content blocks to descriptors
  const blocks = mapMessageContent(message, role);

  return {
    id: generateMessageId(index, prefix),
    role,
    label,
    blocks,
  };
};

/**
 * Maps OpenAI input format to LLMMapperResult
 */
const mapOpenAIInput = (data: OpenAIInputData): LLMMapperResult => {
  if (!data.messages || !Array.isArray(data.messages)) {
    return { messages: [] };
  }

  const messages = data.messages.map((msg, index) =>
    mapOpenAIMessage(msg, index, "input"),
  );

  return { messages };
};

/**
 * Maps OpenAI output format to LLMMapperResult
 */
const mapOpenAIOutput = (data: OpenAIOutputData): LLMMapperResult => {
  if (!data.choices || !Array.isArray(data.choices)) {
    return { messages: [] };
  }

  const messages = data.choices.map((choice, index) => {
    const message = mapOpenAIMessage(choice.message, index, "output");

    // Add finish reason to message if available
    if (choice.finish_reason) {
      message.finishReason = choice.finish_reason;
    }

    return message;
  });

  return {
    messages,
    usage: data.usage,
  };
};

/**
 * Maps direct array input format to LLMMapperResult
 */
const mapDirectArrayInput = (data: OpenAIDirectArrayInput): LLMMapperResult => {
  if (!Array.isArray(data) || data.length === 0) {
    return { messages: [] };
  }

  const messages = data.map((msg, index) =>
    mapOpenAIMessage(msg, index, "input"),
  );

  return { messages };
};

/**
 * Maps custom input format to LLMMapperResult
 */
const mapCustomInputFormat = (
  data: OpenAICustomInputFormat,
): LLMMapperResult => {
  if (!data.input || !Array.isArray(data.input)) {
    return { messages: [] };
  }

  const messages = data.input.map((msg, index) =>
    mapCustomInputMessage(msg, index, "input"),
  );

  return { messages };
};

/**
 * Maps custom output format to LLMMapperResult
 */
const mapCustomOutputFormat = (
  data: OpenAICustomOutputFormat,
): LLMMapperResult => {
  if (typeof data.text !== "string") {
    return { messages: [] };
  }

  // Create a single assistant message with the text content
  const blocks: LLMBlockDescriptor[] = [
    {
      blockType: "text",
      component: PrettyLLMMessage.TextBlock,
      props: {
        children: data.text,
        role: "assistant" as MessageRole,
        showMoreButton: true,
      },
    },
  ];

  const message: LLMMessageDescriptor = {
    id: generateMessageId(0, "output"),
    role: "assistant" as MessageRole,
    blocks,
  };

  // Add finish reason if available
  if (data.finish_reason) {
    message.finishReason = data.finish_reason;
  }

  return {
    messages: [message],
    usage: data.usage,
  };
};

/**
 * Maps OpenAI format data to normalized LLMMapperResult.
 * Supports multiple input and output formats.
 */
export const mapOpenAIMessages: ProviderMapper = (data, prettifyConfig) => {
  if (!data) {
    return { messages: [] };
  }

  const isInput = prettifyConfig?.fieldType === "input";
  const isOutput = prettifyConfig?.fieldType === "output";

  if (isInput) {
    // Check for direct array format first
    if (Array.isArray(data)) {
      return mapDirectArrayInput(data as OpenAIDirectArrayInput);
    }

    // Check for custom input format { input: [...] }
    if (typeof data === "object" && "input" in data) {
      return mapCustomInputFormat(data as OpenAICustomInputFormat);
    }

    // Standard format { messages: [...] }
    if (typeof data === "object" && "messages" in data) {
      return mapOpenAIInput(data as OpenAIInputData);
    }
  }

  if (isOutput) {
    // Check for custom output format { text: "...", usage: {...}, ... }
    if (
      typeof data === "object" &&
      "text" in data &&
      typeof (data as OpenAICustomOutputFormat).text === "string"
    ) {
      return mapCustomOutputFormat(data as OpenAICustomOutputFormat);
    }

    // Standard format { choices: [...] }
    if (typeof data === "object" && "choices" in data) {
      return mapOpenAIOutput(data as OpenAIOutputData);
    }
  }

  return { messages: [] };
};
