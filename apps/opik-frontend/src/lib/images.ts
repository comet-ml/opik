import get from "lodash/get";
import isString from "lodash/isString";
import uniq from "lodash/uniq";

export type ImageContent = {
  type: "image_url";
  image_url: {
    url: string;
  };
};

const isImageContent = (content?: Partial<ImageContent>) => {
  try {
    return content?.type === "image_url" && isString(content?.image_url?.url);
  } catch (error) {
    return false;
  }
};

function extractOpenAIImages(messages: unknown) {
  if (!Array.isArray(messages)) return [];

  const images: string[] = [];

  messages.forEach((message) => {
    const imageContent: ImageContent[] = Array.isArray(message?.content)
      ? message.content.filter(isImageContent)
      : [];

    images.push(...imageContent.map((content) => content.image_url.url));
  });

  return images;
}

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
const DATA_IMAGE_PREFIX = `"data:image/[^;]{3,4};base64,${IMAGE_CHARS_REGEX}"`;
const IMAGE_URL_REGEX = `"https?:\\/\\/[^\\s"']+\\.(${IMAGE_URL_EXTENSIONS.join(
  "|",
)})(\\?[^"']*)?(#[^"']*)?"`;

function extractInputImages(input?: object) {
  if (!input) return [];

  const images: string[] = [];
  const stringifiedInput = JSON.stringify(input);

  // Extract images with general base64 prefix in case it is present
  Object.entries(BASE64_PREFIXES_MAP).forEach(([prefix, extension]) => {
    const regex = new RegExp(`"${prefix}={0,2}${IMAGE_CHARS_REGEX}"`, "g");
    const matches = stringifiedInput.match(regex);

    if (matches) {
      const customPrefixImages = matches.map((match) => {
        const base64Image = match.replace(/"/g, "");
        return `data:image/${extension};base64,${base64Image}`;
      });

      images.push(...customPrefixImages);
    }
  });

  // Extract data:image/...;base64,...
  const dataImageRegex = new RegExp(DATA_IMAGE_PREFIX, "g");
  const dataImageMatches = stringifiedInput.match(dataImageRegex);
  if (dataImageMatches) {
    images.push(...dataImageMatches.map((match) => match.replace(/"/g, "")));
  }

  // Extract image URLs
  const imageUrlRegex = new RegExp(IMAGE_URL_REGEX, "gi");
  const imageUrlMatches = stringifiedInput.match(imageUrlRegex);
  if (imageUrlMatches) {
    images.push(...imageUrlMatches.map((match) => match.replace(/"/g, "")));
  }

  return images;
}

export function extractImageUrls(input?: object) {
  const openAIImages = extractOpenAIImages(get(input, "messages", []));
  const inputImages = extractInputImages(input);

  return uniq([...openAIImages, ...inputImages]);
}
