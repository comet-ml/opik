import isString from "lodash/isString";

import {
  detectLLMMessages,
  LLMBlockDescriptor,
  LLMMessageDescriptor,
} from "@/shared/PrettyLLMMessage/llmMessages";
import { getFormat } from "@/shared/PrettyLLMMessage/llmMessages/providers/registry";
import { MessageRole } from "@/shared/PrettyLLMMessage/types";
import { isMediaAllowedForRole } from "@/lib/llm";
import {
  AudioPart,
  ImagePart,
  LLM_MESSAGE_ROLE,
  MessageContent,
  TextPart,
  VideoPart,
} from "@/types/llm";
import { Span, SPAN_TYPE, Trace } from "@/types/traces";

export type PlaygroundChatRole =
  | LLM_MESSAGE_ROLE.system
  | LLM_MESSAGE_ROLE.user
  | LLM_MESSAGE_ROLE.assistant;

/**
 * One entry of the chat template the Playground loads: a JSON array of
 * `{ role, content }` (see `parseChatTemplateToLLMMessages`).
 */
export interface PlaygroundChatMessage {
  role: PlaygroundChatRole;
  content: MessageContent;
}

type MediaPart = ImagePart | VideoPart | AudioPart;

// The Playground has no tool role and can't make tool calls, so tool and
// function results are left out, along with the tool calls themselves (they
// map to code blocks, which have no Playground counterpart).
const PLAYGROUND_ROLES: Record<MessageRole, PlaygroundChatRole | null> = {
  system: LLM_MESSAGE_ROLE.system,
  user: LLM_MESSAGE_ROLE.user,
  human: LLM_MESSAGE_ROLE.user,
  assistant: LLM_MESSAGE_ROLE.assistant,
  ai: LLM_MESSAGE_ROLE.assistant,
  tool: null,
  function: null,
};

// Media a provider can be sent: a remote URL or inline data. An attachment
// reference such as "[image_0.png]" can't, so it is skipped.
const SENDABLE_MEDIA_URL = /^(https?:\/\/|data:)/i;

const isSendable = ({ url }: { url: string }) => SENDABLE_MEDIA_URL.test(url);

const getBlockText = (block: LLMBlockDescriptor): string | null =>
  block.blockType === "text" && isString(block.props.children)
    ? block.props.children
    : null;

const getBlockMediaParts = (block: LLMBlockDescriptor): MediaPart[] => {
  switch (block.blockType) {
    case "image":
      return block.props.images
        .filter(isSendable)
        .map(
          ({ url }): ImagePart => ({ type: "image_url", image_url: { url } }),
        );
    case "video":
      return block.props.videos
        .filter(isSendable)
        .map(
          ({ url }): VideoPart => ({ type: "video_url", video_url: { url } }),
        );
    case "audio":
      return block.props.audios
        .filter(isSendable)
        .map(
          ({ url }): AudioPart => ({ type: "audio_url", audio_url: { url } }),
        );
    default:
      return [];
  }
};

const toPlaygroundMessage = (
  message: LLMMessageDescriptor,
): PlaygroundChatMessage | null => {
  const role = PLAYGROUND_ROLES[message.role] ?? null;
  if (!role) return null;

  // The Playground edits one text part per message, so text blocks are merged.
  const text = message.blocks
    .map(getBlockText)
    .filter((t): t is string => isString(t) && t.trim() !== "")
    .join("\n\n");

  // Same rule the Playground applies when a message changes role: only user
  // messages carry media.
  const media = isMediaAllowedForRole(role)
    ? message.blocks.flatMap(getBlockMediaParts)
    : [];

  if (media.length === 0) {
    return text ? { role, content: text } : null;
  }

  const textParts: TextPart[] = text ? [{ type: "text", text }] : [];
  return { role, content: [...textParts, ...media] };
};

/**
 * Converts pretty-message descriptors (any format in the llmMessages
 * registry) into Playground chat messages:
 * - system / user / human / assistant / ai map onto system, user, assistant;
 * - tool and function messages, and tool calls, are dropped;
 * - the text blocks of a message are joined into one text part;
 * - images, videos and audio stay on user messages when they are a URL or a
 *   data URI, anything else is skipped;
 * - a message left with no content is dropped.
 */
export const convertLLMMessagesToPlaygroundMessages = (
  messages: LLMMessageDescriptor[],
): PlaygroundChatMessage[] =>
  messages
    .map(toPlaygroundMessage)
    .filter((m): m is PlaygroundChatMessage => m !== null);

/**
 * Detects the message format of an LLM call input the way the trace viewer's
 * Messages tab does (no format hint) and maps it to Playground chat messages.
 * Takes the raw input rather than the viewer's media-placeholder copy, so
 * image URLs stay usable. Returns an empty array when the input isn't messages.
 */
export const getPlaygroundMessagesFromInput = (
  input: unknown,
): PlaygroundChatMessage[] => {
  const detection = detectLLMMessages(input, { fieldType: "input" });
  const format =
    detection.supported && detection.format
      ? getFormat(detection.format)
      : null;

  if (!format) return [];

  return convertLLMMessagesToPlaygroundMessages(
    format.mapper(input, { fieldType: "input" }).messages,
  );
};

export const getSpanPlaygroundMessages = (
  data: Trace | Span,
): PlaygroundChatMessage[] =>
  "type" in data && data.type === SPAN_TYPE.llm
    ? getPlaygroundMessagesFromInput(data.input)
    : [];

export const canOpenSpanInPlayground = (data: Trace | Span): data is Span =>
  getSpanPlaygroundMessages(data).length > 0;
