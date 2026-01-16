import PrettyLLMMessage from "@/components/shared/PrettyLLMMessage";
import {
  ProviderMapper,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
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

type OpenAIContentItem = OpenAITextContent | OpenAIImageContent;

interface OpenAIToolCall {
  id: string;
  type: string;
  function: {
    name: string;
    arguments: string;
  };
}

interface OpenAIMessage {
  role: string;
  content?: string | OpenAIContentItem[] | null;
  tool_calls?: OpenAIToolCall[];
  tool_call_id?: string;
  name?: string;
  refusal?: string | null;
}

interface OpenAIChoice {
  message: OpenAIMessage;
  index: number;
}

interface OpenAIInputData {
  messages: OpenAIMessage[];
}

interface OpenAIOutputData {
  choices: OpenAIChoice[];
}

/**
 * Generates a unique ID for a message
 */
const generateMessageId = (index: number, prefix: string = "msg"): string => {
  return `${prefix}-${index}-${Date.now()}`;
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
 * Converts OpenAI message content to block descriptors.
 * Note: Placeholders like "[image_0]" are kept as-is in the URL.
 * The ImageBlock component will resolve them using MediaContext.
 */
const mapMessageContent = (
  message: OpenAIMessage,
  role: MessageRole,
): LLMBlockDescriptor[] => {
  const blocks: LLMBlockDescriptor[] = [];

  // Handle string content
  if (typeof message.content === "string") {
    blocks.push({
      blockType: "text",
      component: PrettyLLMMessage.TextBlock,
      props: {
        children: message.content,
        role,
        showMoreButton: true,
      },
    });
  }
  // Handle array content (multimodal)
  else if (Array.isArray(message.content)) {
    // Group images together
    const images: Array<{ url: string; name: string }> = [];

    message.content.forEach((item, index) => {
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
        const url = item.image_url.url;
        // Keep placeholders as-is - they will be resolved by ImageBlock using MediaContext
        images.push({
          url: url, // May be placeholder like "[image_0]", actual URL, or base64
          name: isPlaceholder(url) ? url : getImageName(url, index),
        });
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
  }

  // Handle tool calls (assistant requesting tools)
  if (message.tool_calls && message.tool_calls.length > 0) {
    message.tool_calls.forEach((toolCall) => {
      // Try to parse and pretty-print the arguments JSON
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
  }

  // Handle tool result messages
  if (message.role === "tool" && message.content) {
    // Tool content is typically a string (JSON-like or plain text)
    const content =
      typeof message.content === "string"
        ? message.content
        : JSON.stringify(message.content, null, 2);

    // Try to parse and pretty-print if it's JSON-like
    let formattedContent = content;
    try {
      // Handle Python dict-like strings
      const jsonLike = content.replace(/'/g, '"');
      const parsed = JSON.parse(jsonLike);
      formattedContent = JSON.stringify(parsed, null, 2);
    } catch {
      // Keep original if not parseable
    }

    blocks.push({
      blockType: "code",
      component: PrettyLLMMessage.CodeBlock,
      props: {
        code: formattedContent,
        label: message.name || "Tool result",
      },
    });

    // Remove any text blocks that duplicate the tool content
    return blocks.filter(
      (block) =>
        !(
          block.blockType === "text" && block.props.children === message.content
        ),
    );
  }

  return blocks;
};

/**
 * Maps an OpenAI message to our normalized LLMMessageDescriptor structure
 */
const mapOpenAIMessage = (
  message: OpenAIMessage,
  index: number,
  prefix: string,
): LLMMessageDescriptor => {
  const role = message.role as MessageRole;

  // For tool messages, use the function name as label
  const label = message.role === "tool" ? message.name : undefined;

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
 * Maps OpenAI input format to LLMMessageDescriptor array
 */
const mapOpenAIInput = (data: OpenAIInputData): LLMMessageDescriptor[] => {
  if (!data.messages || !Array.isArray(data.messages)) {
    return [];
  }

  return data.messages.map((msg, index) =>
    mapOpenAIMessage(msg, index, "input"),
  );
};

/**
 * Maps OpenAI output format to LLMMessageDescriptor array
 */
const mapOpenAIOutput = (data: OpenAIOutputData): LLMMessageDescriptor[] => {
  if (!data.choices || !Array.isArray(data.choices)) {
    return [];
  }

  return data.choices.map((choice, index) =>
    mapOpenAIMessage(choice.message, index, "output"),
  );
};

/**
 * Maps OpenAI format data to normalized LLMMessageDescriptor array.
 * Supports both input format (messages array) and output format (choices array).
 */
export const mapOpenAIMessages: ProviderMapper = (data, prettifyConfig) => {
  if (!data || typeof data !== "object") {
    return [];
  }

  const isInput = prettifyConfig?.fieldType === "input";
  const isOutput = prettifyConfig?.fieldType === "output";

  if (isInput) {
    return mapOpenAIInput(data as OpenAIInputData);
  }

  if (isOutput) {
    return mapOpenAIOutput(data as OpenAIOutputData);
  }

  return [];
};
