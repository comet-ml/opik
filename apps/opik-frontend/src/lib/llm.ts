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

export const stringifyMessageContent = (content: LLMMessage["content"], {
  includeImagePlaceholders = true,
}: { includeImagePlaceholders?: boolean } = {}) => {
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
