import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import isString from "lodash/isString";

// Image tag constants
export const IMAGE_TAG_START = "<<<image>>>";
export const IMAGE_TAG_END = "<<</image>>>";
export const VIDEO_TAG_START = "<<<video>>>";
export const VIDEO_TAG_END = "<<</video>>>";

const createImageTagRegex = () => /<{3}image>{3}(.*?)<{3}\/image>{3}/g;
const createVideoTagRegex = () => /<{3}video>{3}(.*?)<{3}\/video>{3}/g;

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

export const hasVideosInContent = (
  content: string | undefined | null,
): boolean => {
  if (!isString(content)) {
    return false;
  }

  return createVideoTagRegex().test(content);
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

export const parseContentWithVideos = (
  content: string | undefined | null,
): { text: string; videos: string[] } => {
  if (!isString(content)) {
    return { text: "", videos: [] };
  }

  const videoRegex = createVideoTagRegex();
  const videos: string[] = [];
  let match;

  while ((match = videoRegex.exec(content)) !== null) {
    const videoContent = match[1].trim();
    if (videoContent) {
      videos.push(videoContent);
    }
  }

  const text = content.replace(createVideoTagRegex(), "").trim();

  return { text, videos };
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

export const combineContentWithVideos = (
  text: string | undefined | null,
  videos: string[],
): string => {
  const safeText = text || "";

  if (!videos || videos.length === 0) {
    return safeText;
  }

  const videoTags = videos
    .map((video) => `${VIDEO_TAG_START}${video}${VIDEO_TAG_END}`)
    .join("\n");

  return safeText ? `${safeText}\n${videoTags}` : videoTags;
};

/**
 * Strips image tags from text, keeping only the URL content
 * Converts: "<<<image>>>https://example.com/image.jpg<<</image>>>" → "https://example.com/image.jpg"
 */
export const stripImageTags = (text: string): string => {
  return text.replace(/<<<image>>>(.*?)<<<\/image>>>/g, "$1");
};

export const stripVideoTags = (text: string): string => {
  return text.replace(/<<<video>>>(.*?)<<<\/video>>>/g, "$1");
};

export const parseContentWithMedia = (
  content: string | undefined | null,
): { text: string; images: string[]; videos: string[] } => {
  const { text: withoutVideos, videos } = parseContentWithVideos(content);
  const { text, images } = parseContentWithImages(withoutVideos);
  return { text, images, videos };
};

export const combineContentWithMedia = (
  text: string | undefined | null,
  images: string[],
  videos: string[],
): string => {
  const withImages = combineContentWithImages(text, images);
  return combineContentWithVideos(withImages, videos);
};
