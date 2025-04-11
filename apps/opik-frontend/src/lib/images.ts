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
const DATA_IMAGE_REGEX = new RegExp(
  `data:image/[^;]{3,4};base64,${IMAGE_CHARS_REGEX}`,
  "g",
);
const IMAGE_URL_REGEX = new RegExp(
  `https?:\\/\\/[^\\s"']+\\.(${IMAGE_URL_EXTENSIONS.join(
    "|",
  )})(\\?[^"'\\\\]*(?<!\\\\))?(#[^"'\\\\]*(?<!\\\\))?`,
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

// here we extracting only URL base images that can have no extension that can be skipped with general regex
const extractOpenAIURLImages = (input: object, images: ParsedImageData[]) => {
  if (isObject(input) && "messages" in input && isArray(input.messages)) {
    input.messages.forEach((message) => {
      (message?.content || []).forEach((content: Partial<ImageContent>) => {
        if (!isImageContent(content)) return;

        const url = content.image_url!.url;
        if (!isImageBase64String(url)) {
          images.push({
            url,
            name: extractFilename(url),
          });
        }
      });
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
