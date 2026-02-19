import isString from "lodash/isString";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";
import uniqBy from "lodash/uniqBy";
import {
  ParsedImageData,
  ParsedMediaData,
  ParsedVideoData,
  ParsedAudioData,
  ATTACHMENT_TYPE,
} from "@/types/attachments";
import { safelyParseJSON } from "@/lib/utils";

/**
 * Check if a string is a backend attachment placeholder pattern.
 * Matches patterns like "[input-attachment-1-1768916401606.wav]",
 * "[output-attachment-1-xxx.wav]", or "[output-attachment-2-9876543210-sdk.json]"
 */
export const isBackendAttachmentPlaceholder = (value: string): boolean => {
  return /^\[(input|output|metadata)-attachment-\d+-\d+(?:-[a-zA-Z0-9]+)?\.\w+\]$/.test(
    value,
  );
};

/**
 * Type guard to check if a value is already parsed media data
 */
export const isParsedMediaData = (value: unknown): value is ParsedMediaData => {
  return (
    isObject(value) &&
    "type" in value &&
    "url" in value &&
    "name" in value &&
    isString(value.url) &&
    isString(value.name)
  );
};

const BASE64_PREFIXES_MAP = {
  "/9j/": "jpeg",
  iVBORw0KGgo: "png",
  R0lGODlh: "gif",
  R0lGODdh: "gif",
  Qk: "bmp",
  SUkq: "tiff",
  TU0A: "tiff",
  UklGR: "webp",
} as const;

function base64ToBytes(base64: string): Uint8Array {
  const binaryStr = atob(base64);
  const len = binaryStr.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryStr.charCodeAt(i);
  }
  return bytes;
}

function isValidBase64Image(base64Str: string): boolean {
  try {
    const bytes = base64ToBytes(base64Str);
    if (bytes.length < 4) return false;

    const hex = Array.from(bytes.slice(0, 12))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");

    const startsWith = (sig: string) => hex.startsWith(sig.toLowerCase());

    // Check for RIFF-based formats first (WebP and WAV both start with RIFF)
    if (startsWith("52494646") && bytes.length >= 12) {
      const format = String.fromCharCode(...bytes.slice(8, 12));
      // Only return true if it's WebP, not WAV or other RIFF formats
      if (format === "WEBP") return true;
      // If it's WAVE or other RIFF format, it's not an image
      return false;
    }

    // Check other image signatures
    const signatures = {
      jpeg: ["ffd8ff"],
      png: ["89504e470d0a1a0a"],
      gif: ["474946383961", "474946383761"],
      bmp: ["424d"],
      tiff: ["49492a00", "4d4d002a"],
    };

    for (const sigs of Object.values(signatures)) {
      for (const sig of sigs) {
        if (startsWith(sig)) return true;
      }
    }

    return false;
  } catch {
    return false;
  }
}

export const IMAGE_URL_EXTENSIONS = [
  "apng",
  "avif",
  "bmp",
  "cr2",
  "djv",
  "djvu",
  "eps",
  "gif",
  "hdp",
  "heic",
  "heif",
  "ico",
  "j2k",
  "jp2",
  "jpeg",
  "jpf",
  "jpg",
  "jpm",
  "jxr",
  "mj2",
  "nef",
  "orf",
  "png",
  "psd",
  "raw",
  "sr2",
  "svg",
  "tif",
  "tiff",
  "wdp",
  "webp",
] as const;

export const VIDEO_URL_EXTENSIONS = [
  "mp4",
  "webm",
  "mov",
  "mkv",
  "avi",
  "m4v",
  "mpg",
  "mpeg",
  "ogv",
] as const;

export const AUDIO_URL_EXTENSIONS = [
  "mp3",
  "wav",
  "ogg",
  "flac",
  "aac",
  "m4a",
  "wma",
  "aiff",
  "opus",
  "webm",
] as const;

export const SUPPORTED_VIDEO_FORMATS = VIDEO_URL_EXTENSIONS.map(
  (ext) => `.${ext}`,
).join(",");
export const SUPPORTED_IMAGE_FORMATS = IMAGE_URL_EXTENSIONS.map(
  (ext) => `.${ext}`,
).join(",");
export const SUPPORTED_AUDIO_FORMATS = AUDIO_URL_EXTENSIONS.map(
  (ext) => `.${ext}`,
).join(",");

/**
 * Extract the file extension from a URL
 * @param url - The URL to extract extension from
 * @returns The lowercase extension without the dot, or null if no extension found
 */
export const getUrlExtension = (url: string): string | null => {
  try {
    // Remove query params and hash fragments
    const cleanUrl = url.split(/[?#]/)[0];
    const lastDot = cleanUrl.lastIndexOf(".");
    const lastSlash = cleanUrl.lastIndexOf("/");

    // Extension must come after the last slash
    if (lastDot === -1 || lastDot < lastSlash) {
      return null;
    }

    const extension = cleanUrl.slice(lastDot + 1).toLowerCase();
    return extension || null;
  } catch {
    return null;
  }
};

/**
 * Check if a URL has an image file extension
 * @param url - The URL to check
 * @returns True if the URL has a recognized image extension
 */
export const hasImageExtension = (url: string): boolean => {
  const ext = getUrlExtension(url);
  if (!ext) return false;
  return (IMAGE_URL_EXTENSIONS as readonly string[]).includes(ext);
};

/**
 * Check if a URL has a video file extension
 * @param url - The URL to check
 * @returns True if the URL has a recognized video extension
 */
export const hasVideoExtension = (url: string): boolean => {
  const ext = getUrlExtension(url);
  if (!ext) return false;
  return (VIDEO_URL_EXTENSIONS as readonly string[]).includes(ext);
};

export const hasAudioExtension = (url: string): boolean => {
  const ext = getUrlExtension(url);
  if (!ext) return false;
  return (AUDIO_URL_EXTENSIONS as readonly string[]).includes(ext);
};

const IMAGE_CHARS_REGEX = "[A-Za-z0-9+/]+={0,2}";
export const DATA_IMAGE_REGEX = new RegExp(
  `data:image/[^;]+;base64,${IMAGE_CHARS_REGEX}`,
  "g",
);
// Exclude characters that are invalid in URLs: whitespace, quotes, angle brackets, curly braces, backslash, pipe, caret, backtick
export const IMAGE_URL_REGEX = new RegExp(
  `https?:\\/\\/[^\\s"'<>{}\\\\|\\^\`]+\\.(${IMAGE_URL_EXTENSIONS.join(
    "|",
  )})(\\?[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?(#[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?`,
  "gi",
);

export const DATA_VIDEO_REGEX = new RegExp(
  `data:video/[^;]+;base64,${IMAGE_CHARS_REGEX}`,
  "gi",
);

export const VIDEO_URL_REGEX = new RegExp(
  `https?:\\/\\/[^\\s"'<>{}\\\\|\\^\`]+\\.(${VIDEO_URL_EXTENSIONS.join(
    "|",
  )})\\b(\\?[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?(#[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?`,
  "gi",
);

export const DATA_AUDIO_REGEX = new RegExp(
  `data:audio/[^;]+;base64,${IMAGE_CHARS_REGEX}`,
  "gi",
);

export const AUDIO_URL_REGEX = new RegExp(
  `https?:\\/\\/[^\\s"'<>{}\\\\|\\^\`]+\\.(${AUDIO_URL_EXTENSIONS.join(
    "|",
  )})\\b(\\?[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?(#[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?`,
  "gi",
);

export type ProcessedInput = {
  media: ParsedMediaData[];
  formattedData: object | undefined;
};

export type ImageContent = {
  type: "image_url";
  image_url: {
    url: string;
  };
};

export const isImageContent = (content?: Partial<ImageContent>) => {
  try {
    return content?.type === "image_url" && isString(content?.image_url?.url);
  } catch (error) {
    return false;
  }
};

export const isImageBase64String = (string?: unknown): boolean => {
  if (isString(string)) {
    if (string.startsWith("data:image/")) {
      return true;
    }

    for (const prefix of Object.keys(BASE64_PREFIXES_MAP)) {
      if (string.startsWith(prefix)) {
        return true;
      }
    }
  }

  return false;
};

type VideoUrlValue = {
  url?: string;
};

type FileValue = {
  file_id?: string;
  file_data?: string;
  format?: string;
};

export type VideoContent =
  | {
      type: "video_url";
      video_url?: VideoUrlValue | string;
      file?: FileValue;
    }
  | {
      type: "file";
      file?: FileValue;
    };

export const isVideoContent = (content?: Partial<VideoContent>) => {
  if (!content || !content.type) {
    return false;
  }

  if (content.type === "video_url") {
    if (typeof content.video_url === "string") {
      return true;
    }
    return isString(content.video_url?.url);
  }

  if (content.type === "file") {
    return Boolean(
      content.file?.file_id ||
        content.file?.file_data ||
        (content.file?.format && content.file.format.startsWith("video/")),
    );
  }

  return false;
};

export const isVideoBase64String = (value?: unknown): boolean => {
  if (!isString(value)) {
    return false;
  }

  if (value.startsWith("data:video/") && value.includes(";base64,")) {
    return true;
  }

  return false;
};

export const isAudioBase64String = (value?: unknown): boolean => {
  if (!isString(value)) {
    return false;
  }

  if (value.startsWith("data:audio/") && value.includes(";base64,")) {
    return true;
  }

  return false;
};

type AudioUrlValue = {
  url?: string;
};

type InputAudioValue = {
  data?: string;
  format?: string;
};

export type AudioContent =
  | {
      type: "audio_url";
      audio_url?: AudioUrlValue | string;
      file?: FileValue;
    }
  | {
      type: "input_audio";
      input_audio?: InputAudioValue;
    }
  | {
      type: "file";
      file?: FileValue;
    };

export const isAudioContent = (content?: Partial<AudioContent>) => {
  if (!content || !content.type) {
    return false;
  }

  if (content.type === "audio_url") {
    if (typeof content.audio_url === "string") {
      return true;
    }
    return isString(content.audio_url?.url);
  }

  if (content.type === "input_audio") {
    return Boolean(content.input_audio?.data);
  }

  if (content.type === "file") {
    return Boolean(
      content.file?.file_id ||
        content.file?.file_data ||
        (content.file?.format && content.file.format.startsWith("audio/")),
    );
  }

  return false;
};

export const extractFilename = (url: string): string => {
  const match = url.match(/[^/\\?#]+(?=[?#"]|$)/);
  return match ? match[0] : url;
};

export const parseImageValue = (
  value: unknown,
): ParsedImageData | undefined => {
  if (!isString(value)) {
    return undefined;
  }

  if (isImageBase64String(value)) {
    return {
      url: value,
      name: "Base64 Image",
    };
  }

  const imageUrlMatch = value.match(IMAGE_URL_REGEX);
  if (imageUrlMatch) {
    return {
      url: imageUrlMatch[0],
      name: extractFilename(imageUrlMatch[0]),
    };
  }

  return undefined;
};

const ensureVideoDataUrl = (value: string, mimeType?: string): string => {
  if (!value) {
    return value;
  }

  if (value.startsWith("data:")) {
    return value;
  }

  if (value.includes(";base64,")) {
    return value;
  }

  const safeMime =
    mimeType && mimeType.startsWith("video/") ? mimeType : "video/mp4";
  return `data:${safeMime};base64,${value}`;
};

export const parseVideoValue = (
  value: unknown,
): ParsedVideoData | undefined => {
  if (!isString(value)) {
    return undefined;
  }

  if (isVideoBase64String(value)) {
    return {
      url: value,
      name: "Base64 Video",
      mimeType: value.slice(5, value.indexOf(";base64")),
    };
  }

  const videoUrlMatch = value.match(VIDEO_URL_REGEX);
  if (videoUrlMatch) {
    return {
      url: videoUrlMatch[0],
      name: extractFilename(videoUrlMatch[0]),
    };
  }

  return undefined;
};

const ensureAudioDataUrl = (value: string, mimeType?: string): string => {
  if (!value) {
    return value;
  }

  if (value.startsWith("data:")) {
    return value;
  }

  if (value.includes(";base64,")) {
    return value;
  }

  const safeMime =
    mimeType && mimeType.startsWith("audio/") ? mimeType : "audio/mpeg";
  return `data:${safeMime};base64,${value}`;
};

export const parseAudioValue = (
  value: unknown,
): ParsedAudioData | undefined => {
  if (!isString(value)) {
    return undefined;
  }

  if (isAudioBase64String(value)) {
    return {
      url: value,
      name: "Base64 Audio",
      mimeType: value.slice(5, value.indexOf(";base64")),
    };
  }

  const audioUrlMatch = value.match(AUDIO_URL_REGEX);
  if (audioUrlMatch) {
    return {
      url: audioUrlMatch[0],
      name: extractFilename(audioUrlMatch[0]),
    };
  }

  return undefined;
};

// here we extracting only URL base images that can have no extension that can be skipped with general regex
const extractOpenAIURLImages = (input: object, images: ParsedImageData[]) => {
  if (isObject(input) && "messages" in input && isArray(input.messages)) {
    input.messages.forEach((message) => {
      if (isArray(message?.content)) {
        message.content.forEach((content: Partial<ImageContent>) => {
          if (!isImageContent(content)) return;

          const url = content.image_url!.url;
          if (!isImageBase64String(url)) {
            // Skip backend attachment placeholders - they will be resolved separately via API
            if (isBackendAttachmentPlaceholder(url)) return;
            // Skip if URL has a video extension
            if (hasVideoExtension(url)) return;

            images.push({
              url,
              name: extractFilename(url),
            });
          }
        });
      }
    });
  }
};

const extractOpenAIVideos = (input: object, videos: ParsedVideoData[]) => {
  if (!isObject(input) || !("messages" in input) || !isArray(input.messages)) {
    return;
  }

  input.messages.forEach((message) => {
    if (!isArray(message?.content)) {
      return;
    }

    message.content.forEach((content: Partial<VideoContent>) => {
      if (!isVideoContent(content)) {
        return;
      }

      const pushVideo = (source: string | undefined, mimeType?: string) => {
        if (!source) return;

        // Skip backend attachment placeholders - they will be resolved separately via API
        if (isBackendAttachmentPlaceholder(source)) return;

        // Skip if URL has an image extension (only check http URLs)
        if (source.startsWith("http") && hasImageExtension(source)) return;

        const url = source;
        const name = url.startsWith("data:")
          ? "Base64 Video"
          : extractFilename(url);
        videos.push({
          url,
          name,
          mimeType,
        });
      };

      if (content.type === "video_url") {
        if (typeof content.video_url === "string") {
          pushVideo(content.video_url);
        } else if (content.video_url?.url) {
          pushVideo(content.video_url.url);
        }
      }

      if (content.type === "file" || content.file) {
        const fileValue = content.file ?? {};
        const { file_id, file_data, format } = fileValue;

        // Skip if it's an audio format
        if (format && format.startsWith("audio/")) {
          return;
        }

        if (file_id) {
          pushVideo(file_id, format);
        } else if (file_data) {
          pushVideo(ensureVideoDataUrl(file_data, format), format);
        }
      }
    });
  });
};

const extractOpenAIAudios = (
  input: object,
  inputString: string,
  audios: ParsedAudioData[],
  startIndex: number,
): { updatedInput: string; nextIndex: number } => {
  if (!isObject(input) || !("messages" in input) || !isArray(input.messages)) {
    return { updatedInput: inputString, nextIndex: startIndex };
  }

  let updatedInput = inputString;
  let index = startIndex;

  const pushAudio = (
    source: string | undefined,
    mimeType?: string,
    replaceInString: boolean = false,
  ) => {
    if (!source) return;

    // Skip backend attachment placeholders - they will be resolved separately via API
    if (isBackendAttachmentPlaceholder(source)) return;

    // Skip if URL has an image or video extension (only check http URLs)
    if (source.startsWith("http") && hasImageExtension(source)) return;
    if (source.startsWith("http") && hasVideoExtension(source)) return;

    const url = source;
    const name = url.startsWith("data:")
      ? "Base64 Audio"
      : extractFilename(url);

    // Replace the raw base64 data in the string with a placeholder
    if (
      replaceInString &&
      !url.startsWith("data:") &&
      !url.startsWith("http")
    ) {
      const placeholder = `[audio_${index}]`;
      // Escape special regex characters in the source string
      const escapedSource = source.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      // Replace only the first occurrence (no 'g' flag) to handle duplicate audio correctly
      updatedInput = updatedInput.replace(
        new RegExp(escapedSource),
        placeholder,
      );
      audios.push({
        url: ensureAudioDataUrl(source, mimeType),
        name: `Base64 Audio: ${placeholder}`,
        mimeType,
        hasPlaceholder: true,
      });
      index++;
    } else {
      audios.push({
        url,
        name,
        mimeType,
      });
    }
  };

  input.messages.forEach((message) => {
    // Handle message-level audio field
    if (message?.audio && isObject(message.audio) && "data" in message.audio) {
      const audioData = message.audio.data;
      if (typeof audioData === "string" && audioData.length > 0) {
        // Default to mpeg for message-level audio if no format specified
        const mimeType = "audio/mpeg";
        pushAudio(audioData, mimeType, true);
      }
    }

    // Handle content array
    if (!isArray(message?.content)) {
      return;
    }

    message.content.forEach((content: Partial<AudioContent>) => {
      if (!isAudioContent(content)) {
        return;
      }

      if (content.type === "audio_url") {
        if (typeof content.audio_url === "string") {
          pushAudio(content.audio_url);
        } else if (content.audio_url?.url) {
          pushAudio(content.audio_url.url);
        }
      }

      if (content.type === "input_audio" && "input_audio" in content) {
        const inputAudio = (content as { input_audio?: InputAudioValue })
          .input_audio;
        if (inputAudio?.data) {
          const format = inputAudio.format || "wav";
          const mimeType = `audio/${format}`;
          pushAudio(inputAudio.data, mimeType, true);
        }
      }

      if (content.type === "file" && "file" in content) {
        const fileValue = content.file ?? {};
        const { file_id, file_data, format } = fileValue;
        if (file_id) {
          pushAudio(file_id, format);
        } else if (file_data) {
          pushAudio(file_data, format, true);
        }
      }
    });
  });

  return { updatedInput, nextIndex: index };
};

const extractDataURIImages = (
  input: string,
  images: ParsedImageData[],
  startIndex: number,
) => {
  let index = startIndex;
  return {
    updatedInput: input.replace(DATA_IMAGE_REGEX, (match) => {
      const name = `[image_${index}]`;
      images.push({
        url: match,
        name: `Base64: ${name}`,
        hasPlaceholder: true,
      });
      index++;
      return name;
    }),
    nextIndex: index,
  };
};

const extractPrefixedBase64Images = (
  input: string,
  images: ParsedImageData[],
  startIndex: number,
) => {
  let updatedInput = input;
  let index = startIndex;

  for (const [prefix, extension] of Object.entries(BASE64_PREFIXES_MAP)) {
    const prefixRegex = new RegExp(`${prefix}${IMAGE_CHARS_REGEX}`, "g");
    updatedInput = updatedInput.replace(prefixRegex, (match) => {
      if (!isValidBase64Image(match)) {
        return match;
      }
      const name = `[image_${index}]`;
      images.push({
        url: `data:image/${extension};base64,${match}`,
        name: `Base64: ${name}`,
        hasPlaceholder: true,
      });
      index++;
      return name;
    });
  }

  return {
    updatedInput,
    nextIndex: index,
  };
};

const extractDataURIVideos = (
  input: string,
  videos: ParsedVideoData[],
  startIndex: number,
) => {
  let index = startIndex;
  const updatedInput = input.replace(DATA_VIDEO_REGEX, (match) => {
    const name = `[video_${index}]`;
    videos.push({
      url: match,
      name: `Base64 Video: ${name}`,
      mimeType: match.slice(5, match.indexOf(";base64")),
      hasPlaceholder: true,
    });
    index++;
    return name;
  });

  return {
    updatedInput,
    nextIndex: index,
  };
};

const extractDataURIAudios = (
  input: string,
  audios: ParsedAudioData[],
  startIndex: number,
) => {
  let index = startIndex;
  const updatedInput = input.replace(DATA_AUDIO_REGEX, (match) => {
    const name = `[audio_${index}]`;
    audios.push({
      url: match,
      name: `Base64 Audio: ${name}`,
      mimeType: match.slice(5, match.indexOf(";base64")),
      hasPlaceholder: true,
    });
    index++;
    return name;
  });

  return {
    updatedInput,
    nextIndex: index,
  };
};

const addUniqueMediaUrls = <T extends { url: string; name: string }>(
  input: string,
  regex: RegExp,
  collection: T[],
  createItem: (url: string) => T,
) => {
  const matches = input.match(regex) || [];

  // Keep track of existing items to preserve them (don't deduplicate pre-existing items)
  const existingItems = [...collection];
  const urlMap = new Map<string, T[]>();

  // Build map with arrays to preserve multiple items with same URL
  existingItems.forEach((item) => {
    if (!urlMap.has(item.url)) {
      urlMap.set(item.url, []);
    }
    urlMap.get(item.url)!.push(item);
  });

  matches.forEach((url) => {
    const existingUrls = Array.from(urlMap.keys());

    // Skip if this URL is already contained in a longer existing URL
    const isCovered = existingUrls.some((existing) => existing.includes(url));
    if (isCovered) return;

    // Remove any existing shorter URLs that this URL contains
    existingUrls.forEach((existing) => {
      if (url.includes(existing)) {
        urlMap.delete(existing);
      }
    });

    // Only add if not already in the map
    if (!urlMap.has(url)) {
      urlMap.set(url, [createItem(url)]);
    }
  });

  // Replace collection with all items (preserving duplicates from original collection)
  collection.length = 0;
  urlMap.forEach((items) => collection.push(...items));
};

const extractImageURLs = (input: string, images: ParsedImageData[]) => {
  addUniqueMediaUrls(input, IMAGE_URL_REGEX, images, (url) => ({
    url,
    name: extractFilename(url),
  }));
};

const extractVideoURLs = (input: string, videos: ParsedVideoData[]) => {
  addUniqueMediaUrls(input, VIDEO_URL_REGEX, videos, (url) => ({
    url,
    name: extractFilename(url),
  }));
};

const extractAudioURLs = (input: string, audios: ParsedAudioData[]) => {
  addUniqueMediaUrls(input, AUDIO_URL_REGEX, audios, (url) => ({
    url,
    name: extractFilename(url),
  }));
};

/**
 * Internal function that extracts media WITHOUT deduplication.
 * Use this when you need to preserve all media items including duplicates
 * (e.g., for placeholder resolution in LLM messages).
 *
 * For most use cases, use processInputData instead which deduplicates by URL.
 */
export const processInputDataInternal = (input?: object): ProcessedInput => {
  if (!input) {
    return {
      media: [],
      formattedData: input,
    };
  }

  let inputString = JSON.stringify(input);
  const images: ParsedImageData[] = [];
  const videos: ParsedVideoData[] = [];
  const audios: ParsedAudioData[] = [];
  let imageIndex = 0;
  let videoIndex = 0;
  let audioIndex = 0;

  // STEP 1: Extract media that gets REPLACED with placeholders in the JSON string
  // This ensures array indices match the placeholder numbers in the transformed data

  // Extract OpenAI audios and replace in string (must be before extractDataURIAudios)
  ({ updatedInput: inputString, nextIndex: audioIndex } = extractOpenAIAudios(
    input,
    inputString,
    audios,
    audioIndex,
  ));

  ({ updatedInput: inputString, nextIndex: imageIndex } = extractDataURIImages(
    inputString,
    images,
    imageIndex,
  ));
  ({ updatedInput: inputString, nextIndex: videoIndex } = extractDataURIVideos(
    inputString,
    videos,
    videoIndex,
  ));
  ({ updatedInput: inputString, nextIndex: audioIndex } = extractDataURIAudios(
    inputString,
    audios,
    audioIndex,
  ));
  ({ updatedInput: inputString, nextIndex: imageIndex } =
    extractPrefixedBase64Images(inputString, images, imageIndex));

  // STEP 2: Extract URL-based media (NOT replaced, stays as-is in JSON)
  // These are added to the end of the array after placeholder-based media
  extractOpenAIURLImages(input, images);
  extractOpenAIVideos(input, videos);
  extractImageURLs(inputString, images);
  extractVideoURLs(inputString, videos);
  extractAudioURLs(inputString, audios);

  // Don't deduplicate here - each placeholder needs its own media item
  // even if URLs are identical. This is critical for LLM message components
  // where multiple placeholders like [image_0] and [image_1] may resolve to
  // the same URL but need to be displayed separately.
  const media: ParsedMediaData[] = [
    ...images.map((image) => ({
      ...image,
      type: ATTACHMENT_TYPE.IMAGE as const,
    })),
    ...videos.map((video) => ({
      ...video,
      type: ATTACHMENT_TYPE.VIDEO as const,
    })),
    ...audios.map((audio) => ({
      ...audio,
      type: ATTACHMENT_TYPE.AUDIO as const,
    })),
  ];

  return {
    media,
    formattedData: safelyParseJSON(inputString),
  };
};

/**
 * Processes input data to extract and deduplicate media.
 * This is the main public API that most consumers should use.
 *
 * For LLM message components that need to preserve duplicate URLs with
 * different placeholders, use processInputDataInternal instead.
 */
export const processInputData = (input?: object): ProcessedInput => {
  const result = processInputDataInternal(input);

  // Deduplicate media by URL for normal use cases
  // (dataset items, attachments list, etc.)
  const deduplicatedMedia = uniqBy(result.media, "url");

  return {
    media: deduplicatedMedia,
    formattedData: result.formattedData,
  };
};
