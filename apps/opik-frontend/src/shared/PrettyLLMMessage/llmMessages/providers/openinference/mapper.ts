import {
  OpenInferenceContent,
  OpenInferenceMessage,
  OpenInferenceToolCall,
  parseOpenInferenceFields,
  ParsedOpenInferenceFields,
} from "@/lib/openinference";
import PrettyLLMMessage from "@/shared/PrettyLLMMessage";
import { MessageRole } from "@/shared/PrettyLLMMessage/types";
import {
  FormatCombiner,
  FormatMapper,
  LLMBlockDescriptor,
  LLMMessageDescriptor,
  LLMMapperResult,
} from "../../types";
import { isPlaceholder } from "../../utils";

const normalizeRole = (
  role: string | undefined,
  fieldType: "input" | "output",
): MessageRole => {
  switch (role?.toLowerCase()) {
    case "assistant":
    case "model":
    case "ai":
    case "agent":
      return "assistant";
    case "system":
    case "developer":
      return "system";
    case "tool":
    case "function":
      return "tool";
    case "user":
    case "human":
      return "user";
    default:
      return fieldType === "output" ? "assistant" : "user";
  }
};

const formatCode = (value: unknown): string => {
  if (typeof value === "string") {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};

const mediaName = (
  url: string,
  type: "Image" | "Audio",
  index: number,
): string => {
  if (isPlaceholder(url) || url.startsWith("data:")) {
    return isPlaceholder(url) ? url : `${type} ${index + 1}`;
  }
  try {
    return new URL(url).pathname.split("/").pop() || `${type} ${index + 1}`;
  } catch {
    return `${type} ${index + 1}`;
  }
};

const textBlock = (text: string, role: MessageRole): LLMBlockDescriptor => ({
  blockType: "text",
  component: PrettyLLMMessage.TextBlock,
  props: { children: text, role, showMoreButton: true },
});

const codeBlock = (value: unknown, label: string): LLMBlockDescriptor => ({
  blockType: "code",
  component: PrettyLLMMessage.CodeBlock,
  props: { code: formatCode(value), label },
});

const toolCallFingerprints = (toolCall: OpenInferenceToolCall): string[] => {
  const fingerprints: string[] = [];
  if (toolCall.id) fingerprints.push(`id:${toolCall.id}`);
  if (toolCall.function?.name || toolCall.function?.arguments) {
    fingerprints.push(
      `function:${toolCall.function?.name ?? ""}:${
        toolCall.function?.arguments ?? ""
      }`,
    );
  }
  if (toolCall.reasoning_signature) {
    fingerprints.push(`reasoning:${toolCall.reasoning_signature}`);
  }
  return fingerprints;
};

const toolCallBlock = (toolCall: OpenInferenceToolCall): LLMBlockDescriptor =>
  codeBlock(
    toolCall.function?.arguments ?? "",
    toolCall.function?.name ?? "Tool call",
  );

const contentBlocks = (
  contents: OpenInferenceContent[],
  role: MessageRole,
): { blocks: LLMBlockDescriptor[]; orderedToolCalls: Set<string> } => {
  const blocks: LLMBlockDescriptor[] = [];
  const orderedToolCalls = new Set<string>();

  contents.forEach((content, index) => {
    switch (content.type) {
      case "image": {
        const url = content.image?.url;
        if (url) {
          blocks.push({
            blockType: "image",
            component: PrettyLLMMessage.ImageBlock,
            props: { images: [{ url, name: mediaName(url, "Image", index) }] },
          });
        }
        break;
      }
      case "audio": {
        const url = content.audio?.url;
        if (url) {
          blocks.push({
            blockType: "audio",
            component: PrettyLLMMessage.AudioPlayerBlock,
            props: { audios: [{ url, name: mediaName(url, "Audio", index) }] },
          });
        }
        if (content.audio?.transcript) {
          blocks.push(textBlock(content.audio.transcript, role));
        }
        break;
      }
      case "tool_use": {
        if (content.tool_call) {
          toolCallFingerprints(content.tool_call).forEach((fingerprint) =>
            orderedToolCalls.add(fingerprint),
          );
          blocks.push(toolCallBlock(content.tool_call));
        }
        break;
      }
      case "reasoning":
      case "text":
      default:
        if (content.text) blocks.push(textBlock(content.text, role));
    }
  });

  return { blocks, orderedToolCalls };
};

const mapMessage = (
  message: OpenInferenceMessage,
  index: number,
  fieldType: "input" | "output",
): LLMMessageDescriptor => {
  const role = normalizeRole(message.role, fieldType);
  const blocks: LLMBlockDescriptor[] = [];

  if (message.contents) {
    const mappedContents = contentBlocks(message.contents, role);
    blocks.push(...mappedContents.blocks);
    message.tool_calls
      ?.filter(
        (toolCall) =>
          !toolCallFingerprints(toolCall).some((fingerprint) =>
            mappedContents.orderedToolCalls.has(fingerprint),
          ),
      )
      .forEach((toolCall) => blocks.push(toolCallBlock(toolCall)));
  } else {
    if (message.content !== undefined && message.content !== null) {
      blocks.push(
        role === "tool"
          ? codeBlock(message.content, message.name ?? "Tool result")
          : typeof message.content === "string"
            ? textBlock(message.content, role)
            : codeBlock(message.content, "Content"),
      );
    }
    message.tool_calls?.forEach((toolCall) =>
      blocks.push(toolCallBlock(toolCall)),
    );
  }

  if (message.function_call !== undefined) {
    blocks.push(codeBlock(message.function_call, "Function call"));
  }

  return {
    id: `openinference-${fieldType}-${index}`,
    role,
    label: role === "tool" ? message.name ?? message.tool_call_id : undefined,
    blocks,
  };
};

const fallbackMessage = (
  value: unknown,
  fieldType: "input" | "output",
  index: number,
): LLMMessageDescriptor => {
  const role: MessageRole = fieldType === "output" ? "assistant" : "user";
  return {
    id: `openinference-${fieldType}-fallback-${index}`,
    role,
    blocks: [
      typeof value === "string"
        ? textBlock(value, role)
        : codeBlock(value, fieldType === "output" ? "Output" : "Input"),
    ],
  };
};

const toolLabel = (tool: unknown, index: number): string => {
  if (typeof tool !== "object" || tool === null) return `Tool ${index + 1}`;
  if ("name" in tool && typeof tool.name === "string") return tool.name;
  if (!("json_schema" in tool)) return `Tool ${index + 1}`;

  let schema: unknown = tool.json_schema;
  if (typeof schema === "string") {
    try {
      schema = JSON.parse(schema);
    } catch {
      return `Tool ${index + 1}`;
    }
  }
  if (typeof schema !== "object" || schema === null) return `Tool ${index + 1}`;
  if ("name" in schema && typeof schema.name === "string") return schema.name;
  if (
    "function" in schema &&
    typeof schema.function === "object" &&
    schema.function !== null &&
    "name" in schema.function &&
    typeof schema.function.name === "string"
  ) {
    return schema.function.name;
  }
  return `Tool ${index + 1}`;
};

const mapParsed = (parsed: ParsedOpenInferenceFields): LLMMapperResult => {
  const messages: LLMMessageDescriptor[] = parsed.inputMessages.map(
    (message, index) => mapMessage(message, index, "input"),
  );

  if (parsed.inputMessages.length === 0 && parsed.inputFallback !== undefined) {
    messages.push(fallbackMessage(parsed.inputFallback, "input", 0));
  }

  parsed.prompts.forEach((prompt, index) => {
    messages.push({
      id: `openinference-prompt-${index}`,
      role: "user",
      label: "Prompt",
      blocks: [textBlock(prompt, "user")],
    });
  });

  if (parsed.tools.length > 0) {
    messages.push({
      id: "openinference-tools",
      role: "system",
      label: "Available tools",
      blocks: parsed.tools.map((tool, index) =>
        codeBlock(tool, toolLabel(tool, index)),
      ),
    });
  }

  const outputStart = messages.length;
  messages.push(
    ...parsed.outputMessages.map((message, index) =>
      mapMessage(message, index, "output"),
    ),
  );

  parsed.choices.forEach((choice, index) => {
    messages.push({
      id: `openinference-choice-${index}`,
      role: "assistant",
      label: "Completion",
      blocks: [textBlock(choice, "assistant")],
    });
  });

  if (parsed.functionCall !== undefined) {
    if (messages.length > outputStart) {
      messages[messages.length - 1].blocks.push(
        codeBlock(parsed.functionCall, "Function call"),
      );
    } else {
      messages.push({
        id: "openinference-function-call",
        role: "assistant",
        blocks: [codeBlock(parsed.functionCall, "Function call")],
      });
    }
  }

  // A historical output.value often duplicates the richer llm.output_messages.*
  // attributes that were incorrectly stored in input. Use raw output only as fallback.
  if (
    parsed.outputMessages.length === 0 &&
    parsed.choices.length === 0 &&
    parsed.functionCall === undefined &&
    parsed.outputFallback !== undefined
  ) {
    messages.push(fallbackMessage(parsed.outputFallback, "output", 0));
  }

  if (parsed.finishReason && messages.length > outputStart) {
    messages[messages.length - 1].finishReason = parsed.finishReason;
  }

  return { messages };
};

export const mapOpenInferenceMessages: FormatMapper = (
  data,
  prettifyConfig,
) => {
  const fieldType = prettifyConfig?.fieldType;
  if (!fieldType) return { messages: [] };
  return mapParsed(
    parseOpenInferenceFields(
      fieldType === "input" ? data : undefined,
      fieldType === "output" ? data : undefined,
    ),
  );
};

export const combineOpenInferenceMessages: FormatCombiner = (input, output) =>
  mapParsed(parseOpenInferenceFields(input.raw, output.raw));
