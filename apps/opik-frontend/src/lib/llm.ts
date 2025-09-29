import isArray from "lodash/isArray";
import isString from "lodash/isString";
import uniq from "lodash/uniq";
import mustache from "mustache";

import {
  LLM_MESSAGE_ROLE,
  LLMMessage,
  LLMMessageContentImageUrl,
  LLMMessageContentItem,
} from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import { getPromptMustacheTags } from "@/lib/prompt";

const MUSTACHE_RENDER_OPTIONS = {
  escape: (value: string) => value,
};

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

export const isStructuredMessageContent = (
  content: LLMMessage["content"],
): content is LLMMessageContentItem[] => isArray(content);

export const getMessageContentTextSegments = (
  content: LLMMessage["content"],
) => {
  if (isString(content)) {
    return [content];
  }

  if (isStructuredMessageContent(content)) {
    return content
      .filter(
        (item): item is Extract<LLMMessageContentItem, { type: "text" }> =>
          item.type === "text",
      )
      .map((item) => item.text);
  }

  return [];
};

export const getMessageContentImageSegments = (
  content: LLMMessage["content"],
) => {
  if (!isStructuredMessageContent(content)) {
    return [];
  }

  return content.filter(
    (item): item is LLMMessageContentImageUrl => item.type === "image_url",
  );
};

const isPlainObject = (value: unknown): value is Record<string, unknown> => {
  return typeof value === "object" && value !== null && !Array.isArray(value);
};

const isMessageContentTextItem = (
  value: unknown,
): value is LLMMessageContentItem & { type: "text" } => {
  return isPlainObject(value) && value.type === "text" && isString(value.text);
};

const isMessageContentImageUrlItem = (
  value: unknown,
): value is LLMMessageContentImageUrl => {
  if (!isPlainObject(value) || value.type !== "image_url") {
    return false;
  }

  const imageUrl = value.image_url;

  if (!isPlainObject(imageUrl)) {
    return false;
  }

  const { url, detail } = imageUrl;

  return (
    isString(url) &&
    (detail === undefined || isString(detail))
  );
};

const isMessageContentItemArray = (
  value: unknown,
): value is LLMMessageContentItem[] => {
  return (
    Array.isArray(value) &&
    value.every(
      (item) =>
        isMessageContentTextItem(item) || isMessageContentImageUrlItem(item),
    )
  );
};

export const tryDeserializeMessageContent = (
  template: string,
): LLMMessage["content"] => {
  try {
    const parsed = JSON.parse(template);
    if (isMessageContentItemArray(parsed)) {
      return parsed;
    }
  } catch (error) {
    return template;
  }

  return template;
};

export const stringifyMessageContent = (
  content: LLMMessage["content"],
  {
    includeImagePlaceholders = true,
  }: { includeImagePlaceholders?: boolean } = {},
) => {
  const textSegments = getMessageContentTextSegments(content);
  const imageSegments = getMessageContentImageSegments(content);

  const imagePlaceholders = includeImagePlaceholders
    ? imageSegments.map((segment) => `![image](${segment.image_url.url})`)
    : [];

  return [...textSegments, ...imagePlaceholders].join("\n\n").trim();
};

export const isMessageContentEmpty = (content: LLMMessage["content"]) => {
  if (isString(content)) {
    return content.trim().length === 0;
  }

  if (!isStructuredMessageContent(content)) {
    return true;
  }

  return content.every((item) => {
    if (item.type === "text") {
      return item.text.trim().length === 0;
    }

    if (item.type === "image_url") {
      return item.image_url.url.trim().length === 0;
    }

    return true;
  });
};

export const getMessageContentMustacheTags = (
  content: LLMMessage["content"],
) => {
  if (isString(content)) {
    return getPromptMustacheTags(content);
  }

  if (!isStructuredMessageContent(content)) {
    return [];
  }

  const tags = content.flatMap((item) => {
    if (item.type === "text") {
      return getPromptMustacheTags(item.text);
    }

    if (item.type === "image_url") {
      return getPromptMustacheTags(item.image_url.url);
    }

    return [];
  });

  return uniq(tags);
};

export const safelyGetMessageContentMustacheTags = (
  content: LLMMessage["content"],
) => {
  try {
    return getMessageContentMustacheTags(content);
  } catch (error) {
    return false;
  }
};

export const renderMessageContent = (
  content: LLMMessage["content"],
  data: Record<string, unknown>,
) => {
  if (isString(content)) {
    return mustache.render(content, data, {}, MUSTACHE_RENDER_OPTIONS);
  }

  if (!isStructuredMessageContent(content)) {
    return content;
  }

  return content.map((item) => {
    if (item.type === "text") {
      return {
        ...item,
        text: mustache.render(item.text, data, {}, MUSTACHE_RENDER_OPTIONS),
      };
    }

    if (item.type === "image_url") {
      return {
        ...item,
        image_url: {
          ...item.image_url,
          url: mustache.render(
            item.image_url.url,
            data,
            {},
            MUSTACHE_RENDER_OPTIONS,
          ),
        },
      } satisfies LLMMessageContentImageUrl;
    }

    return item;
  });
};

const IMAGE_MODEL_KEYWORDS = ["gpt-4o", "gpt-4.1", "gpt-5", "o1", "o3", "o4"];

const IMAGE_MODEL_EXACT = new Set(
  [
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-4o-2024-08-06",
    "gpt-4o-2024-05-13",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4-turbo",
    "gpt-4-turbo-preview",
    "gpt-4-turbo-2024-04-09",
    "gpt-4o-mini-2024-07-18",
    "gpt-4o-mini",
    "gpt-5",
    "gpt-5-mini",
    "gpt-5-chat-latest",
    "o1",
    "o1-mini",
    "o3",
    "o3-mini",
    "o4-mini",
  ].map((model) => model.toLowerCase()),
);

export const supportsImageInput = (model?: string | null): boolean => {
  if (!model) {
    return false;
  }

  const normalized = model.toLowerCase();

  if (IMAGE_MODEL_EXACT.has(normalized)) {
    return true;
  }

  return IMAGE_MODEL_KEYWORDS.some((keyword) => normalized.includes(keyword));
};
