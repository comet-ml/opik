import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import isString from "lodash/isString";

// Image tag constants
export const IMAGE_TAG_START = "<<<image>>>";
export const IMAGE_TAG_END = "<<</image>>>";

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

export const hasImagesInContent = (
  content: string | undefined | null,
): boolean => {
  if (!isString(content)) {
    return false;
  }

  return createImageTagRegex().test(content);
};

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

export const combineContentWithImages = (
  text: string | undefined | null,
  images: string[],
): string => {
  const safeText = text || "";

  if (!images || images.length === 0) {
    return safeText;
  }

  const imageTags = images
    .map((image) => `${IMAGE_TAG_START}${image}${IMAGE_TAG_END}`)
    .join("\n");

  return safeText ? `${safeText}\n${imageTags}` : imageTags;
};

/**
 * Strips image tags from text, keeping only the URL content
 * Converts: "<<<image>>>https://example.com/image.jpg<<</image>>>" → "https://example.com/image.jpg"
 */
export const stripImageTags = (text: string): string => {
  return text.replace(/<<<image>>>(.*?)<<<\/image>>>/g, "$1");
};

/**
 * Counts the total number of images across all messages in a prompt
 */
export const countImagesInMessages = (
  messages: Array<{ content?: string | null }>,
): number => {
  return messages.reduce((total, message) => {
    if (!message.content) return total;
    const { images } = parseContentWithImages(message.content);
    return total + images.length;
  }, 0);
};
