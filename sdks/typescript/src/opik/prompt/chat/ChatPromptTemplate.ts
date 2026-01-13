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
        renderedContent = "";
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
    const textParts: string[] = [];

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
            // Modality not supported - add placeholder
            textParts.push("<<<image>>><<</image>>>");
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
            // Modality not supported - add placeholder
            textParts.push("<<<video>>><<</video>>>");
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

    // If we have text placeholders and no structured content, collapse to string
    if (textParts.length > 0 && renderedParts.length === 0) {
      return textParts.join("\n");
    }

    // If we have text placeholders mixed with structured content, add them as text parts
    if (textParts.length > 0) {
      for (const text of textParts) {
        renderedParts.push({
          type: "text",
          text,
        });
      }
    }

    return renderedParts;
  }
}
