import React, { useMemo } from "react";
import get from "lodash/get";
import isString from "lodash/isString";
import uniq from "lodash/uniq";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

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

const IMAGE_CHARS_REGEX = "[A-Za-z0-9+/]+={0,2}";
const DATA_IMAGE_PREFIX = `"data:image/[^;]{3,4};base64,${IMAGE_CHARS_REGEX}"`;

function extractInputImages(input: object) {
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

  return images;
}

function extractImageUrls(input: object) {
  const openAIImages = extractOpenAIImages(get(input, "messages", []));
  const inputImages = extractInputImages(input);

  return uniq([...openAIImages, ...inputImages]);
}

type InputOutputTabProps = {
  data: Trace | Span;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
}) => {
  const imagesUrls = useMemo(() => extractImageUrls(data.input), [data.input]);

  const hasImages = imagesUrls.length > 0;

  const openSections = useMemo(() => {
    return hasImages ? ["images", "input", "output"] : ["input", "output"];
  }, [hasImages]);

  return (
    <Accordion type="multiple" className="w-full" defaultValue={openSections}>
      {hasImages && (
        <AccordionItem value="images">
          <AccordionTrigger>Images</AccordionTrigger>
          <AccordionContent>
            <div className="flex flex-wrap gap-2">
              {imagesUrls.map((imageUrl, index) => {
                return (
                  <div
                    key={index + imageUrl.substring(0, 10)}
                    className="h-[200px] max-w-[300px] rounded-md border p-4"
                  >
                    <img
                      src={imageUrl}
                      loading="lazy"
                      alt={`image-${index}`}
                      className="size-full object-contain"
                    />
                  </div>
                );
              })}
            </div>
          </AccordionContent>
        </AccordionItem>
      )}
      <AccordionItem value="input">
        <AccordionTrigger>Input</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.input} />
        </AccordionContent>
      </AccordionItem>
      <AccordionItem value="output">
        <AccordionTrigger>Output</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.output} />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default InputOutputTab;
