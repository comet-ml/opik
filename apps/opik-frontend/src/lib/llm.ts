import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import isString from "lodash/isString";

/**
 * Creates a fresh regex instance for matching image tags
 * Images are in the format: <<<image>>>IMAGE_URL_OR_BASE64<<</image>>>
 */
const createImageTagRegex = () => /<{3}image>{3}(.*?)<{3}\/image>{3}/g;

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

/**
 * Checks if content contains any images
 * Images are in the format: <<<image>>>IMAGE_URL_OR_BASE64<<</image>>>
 */
export const hasImagesInContent = (
  content: string | undefined | null,
): boolean => {
  if (!isString(content)) {
    return false;
  }

  return createImageTagRegex().test(content);
};

/**
 * Parses content to extract images and text
 * Images are in the format: <<<image>>>IMAGE_URL_OR_BASE64<<</image>>>
 * Each image tag is on a new line
 */
export const parseContentWithImages = (
  content: string | undefined | null,
): { text: string; images: string[] } => {
  if (!isString(content)) {
    return { text: "", images: [] };
  }

  const imageRegex = createImageTagRegex();
  const images: string[] = [];
  let match;

  while ((match = imageRegex.exec(content)) !== null) {
    const imageContent = match[1].trim();
    if (imageContent) {
      images.push(imageContent);
    }
  }

  const text = content.replace(createImageTagRegex(), "").trim();

  return { text, images };
};

/**
 * Combines text and images back into content format
 */
export const combineContentWithImages = (
  text: string | undefined | null,
  images: string[],
): string => {
  const safeText = text || "";

  if (!images || images.length === 0) {
    return safeText;
  }

  const imageTags = images
    .map((image) => `<<<image>>>${image}<<</image>>>`)
    .join("\n");

  return safeText ? `${safeText}\n${imageTags}` : imageTags;
};
