import PrettyLLMMessage from "@/shared/PrettyLLMMessage";
import {
  FormatCombiner,
  FormatMapper,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
  LLMMapperResult,
} from "../../types";
import { MessageRole } from "@/shared/PrettyLLMMessage/types";
import { isPlaceholder } from "../../utils";
import { OPENAI_RENDER_LIMITS } from "./limits";

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

type OpenAIMessageListData = OpenAIInputData;

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

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object";

const isMappableOpenAIMessage = (message: unknown): message is OpenAIMessage =>
  isRecord(message) && typeof message.role === "string";

const isMappableOpenAIMessageList = (
  data: unknown,
): data is OpenAIMessageListData =>
  isRecord(data) &&
  Array.isArray(data.messages) &&
  data.messages.length > 0 &&
  data.messages
    .slice(0, OPENAI_RENDER_LIMITS.messages)
    .every(isMappableOpenAIMessage);

const isMappableOpenAIOutput = (data: unknown): data is OpenAIOutputData =>
  isRecord(data) &&
  Array.isArray(data.choices) &&
  data.choices.length > 0 &&
  data.choices
    .slice(0, OPENAI_RENDER_LIMITS.messages)
    .every(
      (choice) => isRecord(choice) && isMappableOpenAIMessage(choice.message),
    );

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
  toolCalls: unknown[],
  blocks: LLMBlockDescriptor[],
): void => {
  let renderedArgumentsLength = 0;
  toolCalls
    .slice(0, OPENAI_RENDER_LIMITS.toolCallsPerMessage)
    .forEach((toolCall) => {
      const functionCall =
        isRecord(toolCall) && isRecord(toolCall.function)
          ? toolCall.function
          : undefined;
      const functionName = functionCall?.name;
      const functionArguments = functionCall?.arguments;

      if (
        typeof functionName !== "string" ||
        typeof functionArguments !== "string"
      ) {
        blocks.push({
          blockType: "code",
          component: PrettyLLMMessage.CodeBlock,
          props: {
            code: "",
            label:
              typeof functionName === "string" ? functionName : "unknown tool",
          },
        });
        return;
      }

      let formattedArgs = functionArguments;
      const exceedsArgumentLimit =
        functionArguments.length > OPENAI_RENDER_LIMITS.toolArgumentsLength ||
        renderedArgumentsLength + functionArguments.length >
          OPENAI_RENDER_LIMITS.toolArgumentsTotalLength;

      if (exceedsArgumentLimit) {
        formattedArgs = `[Tool arguments omitted: payload exceeds the ${OPENAI_RENDER_LIMITS.toolArgumentsLength.toLocaleString()}-character rendering limit]`;
      } else {
        renderedArgumentsLength += functionArguments.length;
        try {
          const parsed = JSON.parse(functionArguments);
          formattedArgs = JSON.stringify(parsed, null, 2);
        } catch {
          // Keep original if not parseable
        }
      }

      blocks.push({
        blockType: "code",
        component: PrettyLLMMessage.CodeBlock,
        props: {
          code: formattedArgs,
          label: functionName,
        },
      });
    });

  if (toolCalls.length > OPENAI_RENDER_LIMITS.toolCallsPerMessage) {
    blocks.push({
      blockType: "code",
      component: PrettyLLMMessage.CodeBlock,
      props: {
        code: `[${
          toolCalls.length - OPENAI_RENDER_LIMITS.toolCallsPerMessage
        } additional tool calls omitted]`,
        label: "Tool calls truncated",
      },
    });
  }
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
  if (Array.isArray(toolCalls) && toolCalls.length > 0) {
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

// Placeholder marker for truncated message lists. The count is deliberately
// not part of the fingerprint: the input and output views of the same
// conversation can be truncated at different points, and the superset check
// only cares whether the output contains the input's real messages.
const TRUNCATED_PLACEHOLDER_FINGERPRINT = "truncated";

// Cap each serialized part so the fingerprint stays bounded on very large
// payloads (the Messages tab renders limited blocks, but the raw message can
// still carry unbounded content and tool arguments).
const FINGERPRINT_PART_MAX_LENGTH = 1000;

const buildOpenAIMessageFingerprint = (message: OpenAIMessage): string => {
  const parts: string[] = [
    message.role,
    message.name ?? "",
    message.tool_call_id ?? "",
    message.refusal ?? "",
  ];

  if (typeof message.content === "string") {
    parts.push(message.content);
  } else if (Array.isArray(message.content)) {
    for (const block of message.content) {
      if (typeof block === "string") {
        parts.push("text:" + block);
      } else if (block && typeof block === "object") {
        const text = (block as OpenAITextContent).text;
        if (typeof text === "string") {
          parts.push("text:" + text);
        } else {
          parts.push((block as { type?: string }).type ?? "block");
        }
      }
    }
  }

  for (const call of message.tool_calls ?? []) {
    if (!call || typeof call !== "object") continue;
    parts.push(
      "call:" + call.function?.name + ":" + (call.function?.arguments ?? ""),
    );
  }

  return parts
    .map((part) => part.slice(0, FINGERPRINT_PART_MAX_LENGTH))
    .join("\u0000");
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
    contentFingerprint: buildOpenAIMessageFingerprint(message),
  };
};

const appendMessageLimitPlaceholder = (
  messages: LLMMessageDescriptor[],
  prefix: string,
  omittedCount: number,
): void => {
  if (omittedCount <= 0) return;

  messages.push({
    id: `${prefix}-truncated`,
    role: "assistant",
    blocks: [
      {
        blockType: "code",
        component: PrettyLLMMessage.CodeBlock,
        props: {
          code: `[${omittedCount} additional messages omitted]`,
          label: "Messages truncated",
        },
      },
    ],
    contentFingerprint: TRUNCATED_PLACEHOLDER_FINGERPRINT,
  });
};

/**
 * Maps OpenAI input format to LLMMapperResult
 */
const mapOpenAIMessageList = (
  data: OpenAIMessageListData,
  prefix: string,
): LLMMapperResult => {
  if (!isMappableOpenAIMessageList(data)) {
    return { messages: [] };
  }

  const messages = data.messages
    .slice(0, OPENAI_RENDER_LIMITS.messages)
    .map((msg, index) => mapOpenAIMessage(msg, index, prefix));
  appendMessageLimitPlaceholder(
    messages,
    prefix,
    data.messages.length - OPENAI_RENDER_LIMITS.messages,
  );

  return { messages };
};

const mapOpenAIInput = (data: OpenAIInputData): LLMMapperResult =>
  mapOpenAIMessageList(data, "input");

/**
 * Maps OpenAI output format to LLMMapperResult
 */
const mapOpenAIOutput = (data: OpenAIOutputData): LLMMapperResult => {
  if (!isMappableOpenAIOutput(data)) {
    return { messages: [] };
  }

  const messages = data.choices
    .slice(0, OPENAI_RENDER_LIMITS.messages)
    .map((choice, index) => {
      const message = mapOpenAIMessage(choice.message, index, "output");

      // Add finish reason to message if available
      if (choice.finish_reason) {
        message.finishReason = choice.finish_reason;
      }

      return message;
    });
  appendMessageLimitPlaceholder(
    messages,
    "output",
    data.choices.length - OPENAI_RENDER_LIMITS.messages,
  );

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

  const messages = data
    .slice(0, OPENAI_RENDER_LIMITS.messages)
    .map((msg, index) => mapOpenAIMessage(msg, index, "input"));
  appendMessageLimitPlaceholder(
    messages,
    "input",
    data.length - OPENAI_RENDER_LIMITS.messages,
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

  const messages = data.input
    .slice(0, OPENAI_RENDER_LIMITS.messages)
    .map((msg, index) => mapCustomInputMessage(msg, index, "input"));
  appendMessageLimitPlaceholder(
    messages,
    "input",
    data.input.length - OPENAI_RENDER_LIMITS.messages,
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

const isOutputSupersetOfInput = (
  inputMsgs: LLMMessageDescriptor[],
  outputMsgs: LLMMessageDescriptor[],
): boolean => {
  // Truncation placeholders are not real messages: the input and output views
  // of the same conversation can be truncated at different points, so compare
  // only the actual messages positionally.
  const inputReal = inputMsgs.filter(
    (message) =>
      message.contentFingerprint !== TRUNCATED_PLACEHOLDER_FINGERPRINT,
  );
  const outputReal = outputMsgs.filter(
    (message) =>
      message.contentFingerprint !== TRUNCATED_PLACEHOLDER_FINGERPRINT,
  );
  if (outputReal.length < inputReal.length) return false;

  return inputReal.every(
    (message, index) =>
      message.contentFingerprint !== undefined &&
      message.contentFingerprint === outputReal[index]?.contentFingerprint,
  );
};

export const combineOpenAIMessages: FormatCombiner = (input, output) => {
  if (isOutputSupersetOfInput(input.mapped.messages, output.mapped.messages)) {
    return {
      messages: output.mapped.messages,
      usage: output.mapped.usage,
    };
  }

  return {
    messages: [...input.mapped.messages, ...output.mapped.messages],
    usage: output.mapped.usage,
  };
};

/**
 * Maps OpenAI format data to normalized LLMMapperResult.
 * Supports multiple input and output formats.
 */
export const mapOpenAIMessages: FormatMapper = (data, prettifyConfig) => {
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
    if (isMappableOpenAIMessageList(data)) {
      return mapOpenAIMessageList(data, "output");
    }

    // Check for custom output format { text: "...", usage: {...}, ... }
    if (isRecord(data) && "text" in data && typeof data.text === "string") {
      return mapCustomOutputFormat(data as unknown as OpenAICustomOutputFormat);
    }

    // Standard format { choices: [...] }
    if (isMappableOpenAIOutput(data)) {
      return mapOpenAIOutput(data);
    }
  }

  return { messages: [] };
};
