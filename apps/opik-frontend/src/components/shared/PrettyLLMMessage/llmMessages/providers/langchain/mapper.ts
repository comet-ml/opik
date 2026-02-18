import PrettyLLMMessage from "@/components/shared/PrettyLLMMessage";
import {
  FormatMapper,
  LLMMessageDescriptor,
  LLMBlockDescriptor,
  LLMMapperResult,
} from "../../types";
import { MessageRole } from "@/components/shared/PrettyLLMMessage/types";
import { isPlaceholder } from "../../utils";

interface LangChainContentItem {
  type: string;
  text?: string;
  image_url?: { url: string; detail?: string };
  input_audio?: { data: string; format: string };
}

interface LangChainToolCall {
  name: string;
  args: Record<string, unknown>;
  id?: string;
  type?: string;
}

interface LangChainMessage {
  type: string;
  content?: string | LangChainContentItem[];
  tool_calls?: LangChainToolCall[];
  name?: string;
  tool_call_id?: string;
  response_metadata?: Record<string, unknown>;
  id?: string | null;
}

interface LangChainGeneration {
  text: string;
  generation_info?: Record<string, unknown>;
  type?: string;
  message?: {
    lc?: number;
    type?: string;
    id?: string[];
    kwargs?: Record<string, unknown>;
  };
}

const generateMessageId = (index: number, prefix: string = "msg"): string => {
  return `${prefix}-${index}`;
};

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

const typeToRole = (type: string): MessageRole => {
  if (type === "chat") return "user";
  return type as MessageRole;
};

const processMultimodalContent = (
  content: LangChainContentItem[],
  role: MessageRole,
  blocks: LLMBlockDescriptor[],
): void => {
  const images: Array<{ url: string; name: string }> = [];

  content.forEach((item, index) => {
    if (item.type === "text") {
      if (images.length > 0) {
        blocks.push({
          blockType: "image",
          component: PrettyLLMMessage.ImageBlock,
          props: { images: [...images] },
        });
        images.length = 0;
      }
      if (item.text) {
        blocks.push({
          blockType: "text",
          component: PrettyLLMMessage.TextBlock,
          props: { children: item.text, role, showMoreButton: true },
        });
      }
    } else if (item.type === "image_url") {
      if (
        item.image_url &&
        typeof item.image_url === "object" &&
        typeof item.image_url.url === "string" &&
        item.image_url.url.length > 0
      ) {
        const url = item.image_url.url;
        images.push({
          url,
          name: isPlaceholder(url) ? url : getImageName(url, index),
        });
      }
    }
  });

  if (images.length > 0) {
    blocks.push({
      blockType: "image",
      component: PrettyLLMMessage.ImageBlock,
      props: { images },
    });
  }
};

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
    props: { code: formattedContent, label: name || "Tool result" },
  };
};

const mapLangChainToolCalls = (
  toolCalls: LangChainToolCall[],
  blocks: LLMBlockDescriptor[],
): void => {
  toolCalls.forEach((toolCall) => {
    const formattedArgs = JSON.stringify(toolCall.args, null, 2);
    blocks.push({
      blockType: "code",
      component: PrettyLLMMessage.CodeBlock,
      props: { code: formattedArgs, label: toolCall.name },
    });
  });
};

const buildLangChainContentBlocks = (
  message: LangChainMessage,
  role: MessageRole,
): LLMBlockDescriptor[] => {
  const blocks: LLMBlockDescriptor[] = [];
  const { content, tool_calls, name } = message;

  if (typeof content === "string") {
    if (role === "tool") {
      blocks.push(formatAsToolResult(content, name));
    } else if (content.length > 0) {
      blocks.push({
        blockType: "text",
        component: PrettyLLMMessage.TextBlock,
        props: { children: content, role, showMoreButton: true },
      });
    }
  } else if (Array.isArray(content)) {
    processMultimodalContent(content, role, blocks);
  }

  if (tool_calls && tool_calls.length > 0) {
    mapLangChainToolCalls(tool_calls, blocks);
  }

  return blocks;
};

const mapLangChainMessage = (
  message: LangChainMessage,
  index: number,
  prefix: string,
): LLMMessageDescriptor => {
  const role = typeToRole(message.type);
  const label = role === "tool" ? message.name : undefined;
  const blocks = buildLangChainContentBlocks(message, role);

  const descriptor: LLMMessageDescriptor = {
    id: generateMessageId(index, prefix),
    role,
    label,
    blocks,
  };

  const finishReason = message.response_metadata?.finish_reason;
  if (typeof finishReason === "string") {
    descriptor.finishReason = finishReason;
  }

  return descriptor;
};

const mapFlatMessages = (
  data: Record<string, unknown>,
  prefix: string,
): LLMMapperResult => {
  const messages = data.messages as LangChainMessage[];
  return {
    messages: messages.map((msg, index) =>
      mapLangChainMessage(msg, index, prefix),
    ),
  };
};

const mapBatchedInput = (data: Record<string, unknown>): LLMMapperResult => {
  const firstBatch = (data.messages as unknown[][])[0] as LangChainMessage[];
  return {
    messages: firstBatch.map((msg, index) =>
      mapLangChainMessage(msg, index, "input"),
    ),
  };
};

const mapGenerationsOutput = (
  data: Record<string, unknown>,
): LLMMapperResult => {
  const firstBatch = (
    data.generations as unknown[][]
  )[0] as LangChainGeneration[];

  const messages: LLMMessageDescriptor[] = firstBatch.map((gen, index) => {
    const content = gen.message?.kwargs?.content ?? gen.text;
    const contentStr =
      typeof content === "string" ? content : JSON.stringify(content, null, 2);

    const blocks: LLMBlockDescriptor[] = [
      {
        blockType: "text",
        component: PrettyLLMMessage.TextBlock,
        props: {
          children: contentStr,
          role: "ai" as MessageRole,
          showMoreButton: true,
        },
      },
    ];

    const descriptor: LLMMessageDescriptor = {
      id: generateMessageId(index, "output"),
      role: "ai" as MessageRole,
      blocks,
    };

    const finishReason = gen.generation_info?.finish_reason;
    if (typeof finishReason === "string") {
      descriptor.finishReason = finishReason;
    }

    return descriptor;
  });

  const llmOutput = data.llm_output as
    | { token_usage?: Record<string, number> }
    | undefined;
  const usage = llmOutput?.token_usage
    ? {
        prompt_tokens: llmOutput.token_usage.prompt_tokens,
        completion_tokens: llmOutput.token_usage.completion_tokens,
        total_tokens: llmOutput.token_usage.total_tokens,
      }
    : undefined;

  return { messages, usage };
};

export const mapLangChainMessages: FormatMapper = (data, prettifyConfig) => {
  if (!data) return { messages: [] };

  const isInput = prettifyConfig?.fieldType === "input";
  const isOutput = prettifyConfig?.fieldType === "output";

  if (!isInput && !isOutput) return { messages: [] };

  const d = data as Record<string, unknown>;

  if (isInput && Array.isArray(d.messages)) {
    if (Array.isArray(d.messages[0])) {
      return mapBatchedInput(d);
    }
    return mapFlatMessages(d, "input");
  }

  if (isOutput) {
    if (Array.isArray(d.messages) && !Array.isArray(d.messages[0])) {
      return mapFlatMessages(d, "output");
    }
    if (Array.isArray(d.generations)) {
      return mapGenerationsOutput(d);
    }
  }

  return { messages: [] };
};
