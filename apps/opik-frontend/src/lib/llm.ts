import isArray from "lodash/isArray";
import isString from "lodash/isString";
import uniq from "lodash/uniq";
import mustache from "mustache";

import modelPricing from "@/data/model_prices_and_context_window.json";
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

  return isString(url) && (detail === undefined || isString(detail));
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

type ModelPricingEntry = {
  supports_vision?: boolean;
  [key: string]: unknown;
};

const normalizeModelName = (value: string) => value.trim().toLowerCase();

const modelEntries = modelPricing as Record<string, ModelPricingEntry>;

const VISION_CAPABILITIES = new Map<string, boolean>();
const NORMALIZED_VISION_CAPABILITIES = new Map<string, boolean>();

Object.entries(modelEntries).forEach(([modelName, entry]) => {
  if (!modelName) {
    return;
  }

  const supportsVision = Boolean(entry?.supports_vision);
  VISION_CAPABILITIES.set(modelName, supportsVision);
  NORMALIZED_VISION_CAPABILITIES.set(
    normalizeModelName(modelName),
    supportsVision,
  );
});

const candidateKeys = (modelName: string): string[] => {
  const normalized = normalizeModelName(modelName);
  const candidates = new Set<string>([normalized]);

  const slashIndex = normalized.lastIndexOf("/") + 1;
  if (slashIndex > 0 && slashIndex < normalized.length) {
    candidates.add(normalized.slice(slashIndex));
  }

  const colonIndex = normalized.indexOf(":");
  if (colonIndex > 0) {
    candidates.add(normalized.slice(0, colonIndex));

    if (slashIndex > 0 && slashIndex < colonIndex) {
      candidates.add(normalized.slice(slashIndex, colonIndex));
    }
  }

  return Array.from(candidates);
};

export const supportsImageInput = (model?: string | null): boolean => {
  if (!model) {
    return false;
  }

  const exact = VISION_CAPABILITIES.get(model);
  if (exact !== undefined) {
    return exact;
  }

  for (const key of candidateKeys(model)) {
    const capability = NORMALIZED_VISION_CAPABILITIES.get(key);
    if (capability !== undefined) {
      return capability;
    }
  }

  return false;
};
