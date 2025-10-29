import { formatPromptTemplate } from "@/prompt/formatting";
import type { PromptType } from "@/prompt/types";
import type {
  MessageContent,
  MessageContentImageUrlPart,
  MessageContentPart,
  MessageContentTextPart,
} from "../models/OpikBaseModel";

const IMAGE_PLACEHOLDER_PREFIX = "<<<image>>>";
const IMAGE_PLACEHOLDER_SUFFIX = "<<</image>>>";

export interface RenderMessageContentOptions {
  content: MessageContent;
  variables: Record<string, unknown>;
  templateType: PromptType;
  supportsVision: boolean;
}

export function renderMessageContent({
  content,
  variables,
  templateType,
  supportsVision,
}: RenderMessageContentOptions): MessageContent {
  if (typeof content === "string") {
    return decodeHtmlEntities(
      formatPromptTemplate(content, variables, templateType)
    );
  }

  if (!Array.isArray(content)) {
    return String(content ?? "");
  }

  const renderedParts: MessageContentPart[] = [];

  for (const part of content) {
    if (!part || typeof part !== "object") {
      continue;
    }

    if (part.type === "text") {
      const textPart = part as MessageContentTextPart;
      const renderedText = decodeHtmlEntities(
        formatPromptTemplate(textPart.text ?? "", variables, templateType)
      );
      if (renderedText) {
        renderedParts.push({
          type: "text",
          text: renderedText,
        });
      }
      continue;
    }

    if (part.type === "image_url") {
      const imagePart = part as MessageContentImageUrlPart;
      const urlTemplate = imagePart.image_url?.url ?? "";
      const renderedUrl = decodeHtmlEntities(
        formatPromptTemplate(urlTemplate, variables, templateType)
      );
      if (!renderedUrl) {
        continue;
      }

      const renderedImage: MessageContentImageUrlPart = {
        type: "image_url",
        image_url: {
          url: renderedUrl,
        },
      };

      if (imagePart.image_url?.detail) {
        renderedImage.image_url.detail = imagePart.image_url.detail;
      }

      renderedParts.push(renderedImage);
    }
  }

  if (!supportsVision) {
    return flattenToText(renderedParts);
  }

  return renderedParts;
}

export function flattenToText(parts: MessageContentPart[]): string {
  const segments: string[] = [];

  for (const part of parts) {
    if (part.type === "text") {
      const textPart = part as MessageContentTextPart;
      if (textPart.text) {
        segments.push(textPart.text);
      }
      continue;
    }

    if (part.type === "image_url") {
      const imagePart = part as MessageContentImageUrlPart;
      const url = imagePart.image_url?.url;
      if (url) {
        segments.push(
          `${IMAGE_PLACEHOLDER_PREFIX}${url}${IMAGE_PLACEHOLDER_SUFFIX}`
        );
      }
    }
  }

  return segments.join("\n\n");
}

export { IMAGE_PLACEHOLDER_PREFIX, IMAGE_PLACEHOLDER_SUFFIX };

function decodeHtmlEntities(value: string): string {
  return value
    .replace(/&#x2F;/g, "/")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}
