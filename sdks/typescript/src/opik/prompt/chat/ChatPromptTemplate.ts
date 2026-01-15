import type {
  ChatMessage,
  ContentPart,
  MessageContent,
  PromptType,
  PromptVariables,
  SupportedModalities,
  TextContentPart,
  ImageUrlContentPart,
  VideoUrlContentPart,
} from "../types";
import { formatPromptTemplate } from "../formatting";
import { PromptValidationError } from "../errors";

/**
 * Template for chat-style prompts with multimodal content support.
 * Handles OpenAI-like message formats with text, images, and video content.
 */
export class ChatPromptTemplate {
  private messages: ChatMessage[];
  private templateType: PromptType;
  private validatePlaceholders: boolean;

  constructor(
    messages: ChatMessage[],
    templateType: PromptType = "mustache",
    validatePlaceholders: boolean = false,
  ) {
    this.messages = messages;
    this.templateType = templateType;
    this.validatePlaceholders = validatePlaceholders;
  }

  /**
   * Format the chat template with provided variables.
   * Supports multimodal content with modality filtering.
   */
  format(
    variables: PromptVariables,
    supportedModalities?: SupportedModalities,
  ): ChatMessage[] {
    // Default to all modalities supported
    const modalities: SupportedModalities = {
      vision: true,
      video: true,
      ...supportedModalities,
    };

    const renderedMessages: ChatMessage[] = [];

    for (const message of this.messages) {
      const role = message.role;
      if (!role) {
        continue;
      }

      const content = message.content;
      let renderedContent: MessageContent;

      if (typeof content === "string") {
        // Simple string content - just render template
        renderedContent = formatPromptTemplate(
          content,
          variables,
          this.templateType,
        );
      } else if (Array.isArray(content)) {
        // Multimodal content - render each part
        renderedContent = this.renderContentParts(
          content,
          variables,
          modalities,
        );
      } else {
        throw new PromptValidationError(
          `Invalid message content type. Expected string or array of content parts, got: ${typeof content}`,
        );
      }

      renderedMessages.push({
        role,
        content: renderedContent,
      });
    }

    return renderedMessages;
  }

  /**
   * Render an array of content parts
   */
  private renderContentParts(
    parts: ContentPart[],
    variables: PromptVariables,
    modalities: SupportedModalities,
  ): ContentPart[] | string {
    const renderedParts: ContentPart[] = [];
    let shouldFlatten = false;

    for (const part of parts) {
      if (!part || typeof part !== "object") {
        continue;
      }

      const type = part.type;

      switch (type) {
        case "text": {
          const textPart = part as TextContentPart;
          const renderedText = formatPromptTemplate(
            textPart.text || "",
            variables,
            this.templateType,
          );
          renderedParts.push({
            type: "text",
            text: renderedText,
          });
          break;
        }

        case "image_url": {
          if (modalities.vision === false) {
            // Modality not supported - add placeholder at this position
            shouldFlatten = true;
            renderedParts.push({
              type: "text",
              text: "<<<image>>><<</image>>>",
            });
          } else {
            const imagePart = part as ImageUrlContentPart;
            const imageUrl = imagePart.image_url?.url || "";
            const renderedUrl = formatPromptTemplate(
              imageUrl,
              variables,
              this.templateType,
            );
            if (renderedUrl) {
              const rendered: ImageUrlContentPart = {
                type: "image_url",
                image_url: {
                  url: renderedUrl,
                },
              };
              if (imagePart.image_url?.detail) {
                rendered.image_url.detail = imagePart.image_url.detail;
              }
              renderedParts.push(rendered);
            }
          }
          break;
        }

        case "video_url": {
          if (modalities.video === false) {
            // Modality not supported - add placeholder at this position
            shouldFlatten = true;
            renderedParts.push({
              type: "text",
              text: "<<<video>>><<</video>>>",
            });
          } else {
            const videoPart = part as VideoUrlContentPart;
            const videoUrl = videoPart.video_url?.url || "";
            const renderedUrl = formatPromptTemplate(
              videoUrl,
              variables,
              this.templateType,
            );
            if (renderedUrl) {
              const rendered: VideoUrlContentPart = {
                type: "video_url",
                video_url: {
                  url: renderedUrl,
                },
              };
              // Preserve optional metadata
              if (videoPart.video_url?.mime_type) {
                rendered.video_url.mime_type = videoPart.video_url.mime_type;
              }
              if (videoPart.video_url?.duration) {
                rendered.video_url.duration = videoPart.video_url.duration;
              }
              if (videoPart.video_url?.format) {
                rendered.video_url.format = videoPart.video_url.format;
              }
              if (videoPart.video_url?.detail) {
                rendered.video_url.detail = videoPart.video_url.detail;
              }
              renderedParts.push(rendered);
            }
          }
          break;
        }

        default:
          // Unknown content type - pass through
          renderedParts.push(part);
      }
    }

    // If any unsupported modality was encountered, flatten all parts to a string
    if (shouldFlatten) {
      const segments: string[] = [];
      for (const part of renderedParts) {
        if (part.type === "text") {
          const text = (part as TextContentPart).text;
          if (text) {
            segments.push(text);
          }
        } else {
          // For non-text parts that are still in the array (shouldn't happen when flattening,
          // but handle gracefully), convert to string representation
          segments.push(JSON.stringify(part));
        }
      }
      return segments.join("\n\n");
    }

    // If renderedParts contains exactly one text part, collapse to string
    if (
      renderedParts.length === 1 &&
      renderedParts[0].type === "text"
    ) {
      return (renderedParts[0] as TextContentPart).text;
    }

    return renderedParts;
  }
}
