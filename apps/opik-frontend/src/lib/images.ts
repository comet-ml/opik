import isString from "lodash/isString";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";
import uniqBy from "lodash/uniqBy";
import { ParsedImageData } from "@/types/attachments";
import { safelyParseJSON } from "@/lib/utils";

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

    const signatures = {
      jpeg: ["ffd8ff"],
      png: ["89504e470d0a1a0a"],
      gif: ["474946383961", "474946383761"],
      bmp: ["424d"],
      tiff: ["49492a00", "4d4d002a"],
      webp: ["52494646"], // needs extra check
    };

    for (const sigs of Object.values(signatures)) {
      for (const sig of sigs) {
        if (startsWith(sig)) return true;
      }
    }

    // WebP check
    if (startsWith("52494646") && bytes.length >= 12) {
      const format = String.fromCharCode(...bytes.slice(8, 12));
      if (format === "WEBP") return true;
    }

    return false;
  } catch {
    return false;
  }
}

const IMAGE_URL_EXTENSIONS = [
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

const IMAGE_CHARS_REGEX = "[A-Za-z0-9+/]+={0,2}";
export const DATA_IMAGE_REGEX = new RegExp(
  `data:image/[^;]{3,4};base64,${IMAGE_CHARS_REGEX}`,
  "g",
);
// Exclude characters that are invalid in URLs: whitespace, quotes, angle brackets, curly braces, backslash, pipe, caret, backtick
export const IMAGE_URL_REGEX = new RegExp(
  `https?:\\/\\/[^\\s"'<>{}\\\\|\\^\`]+\\.(${IMAGE_URL_EXTENSIONS.join(
    "|",
  )})(\\?[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?(#[^"'<>{}\\\\|\\^\`]*(?<!\\\\))?`,
  "gi",
);

export type ProcessedInput = {
  images: ParsedImageData[];
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

// here we extracting only URL base images that can have no extension that can be skipped with general regex
const extractOpenAIURLImages = (input: object, images: ParsedImageData[]) => {
  if (isObject(input) && "messages" in input && isArray(input.messages)) {
    input.messages.forEach((message) => {
      if (isArray(message?.content)) {
        message.content.forEach((content: Partial<ImageContent>) => {
          if (!isImageContent(content)) return;

          const url = content.image_url!.url;
          if (!isImageBase64String(url)) {
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

const extractImageURLs = (input: string, images: ParsedImageData[]) => {
  const matches = input.match(IMAGE_URL_REGEX) || [];
  matches.forEach((url) => {
    images.push({
      url: url,
      name: extractFilename(url),
    });
  });
};

export const processInputData = (input?: object): ProcessedInput => {
  if (!input) {
    return { images: [], formattedData: input };
  }

  let inputString = JSON.stringify(input);
  const images: ParsedImageData[] = [];
  let index = 0;

  extractOpenAIURLImages(input, images);

  ({ updatedInput: inputString, nextIndex: index } = extractDataURIImages(
    inputString,
    images,
    index,
  ));
  ({ updatedInput: inputString, nextIndex: index } =
    extractPrefixedBase64Images(inputString, images, index));
  extractImageURLs(inputString, images);

  return {
    images: uniqBy(images, "url"),
    formattedData: safelyParseJSON(inputString),
  };
};
