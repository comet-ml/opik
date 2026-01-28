// ============================================================================
// BLOCK WIDGETS
// ============================================================================
// Block-level widgets for section structure and content display.
// Each widget has a clear API/props and exported config for registry building.

import type { ComponentRenderProps, ComponentRegistry } from "@/lib/data-view";

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Safely converts a value to a displayable string.
 * - Objects/arrays are JSON stringified with formatting
 * - Strings are returned as-is
 * - null/undefined return empty string
 * - Other primitives are converted via String()
 */
function toDisplayString(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return "[Complex Object]";
    }
  }
  return String(value);
}

/**
 * Checks if a value is JSON (object or array, not a string)
 */
function isJsonValue(value: unknown): boolean {
  return (
    value !== null &&
    typeof value === "object" &&
    !(value instanceof Date) &&
    !(value instanceof RegExp)
  );
}

// Components
export { HeaderWidget, headerWidgetConfig } from "./HeaderWidget";
export type { HeaderWidgetProps, HeaderLevel } from "./HeaderWidget";

export { DividerWidget, dividerWidgetConfig } from "./DividerWidget";
export type { DividerWidgetProps } from "./DividerWidget";

export { TextBlockWidget, textBlockWidgetConfig } from "./TextBlockWidget";
export type { TextBlockWidgetProps } from "./TextBlockWidget";

export { CodeWidget, codeWidgetConfig } from "./CodeWidget";
export type { CodeWidgetProps } from "./CodeWidget";

export { ImageWidget, imageWidgetConfig } from "./ImageWidget";
export type { ImageWidgetProps } from "./ImageWidget";

export { VideoWidget, videoWidgetConfig } from "./VideoWidget";
export type { VideoWidgetProps } from "./VideoWidget";

export { AudioWidget, audioWidgetConfig } from "./AudioWidget";
export type { AudioWidgetProps } from "./AudioWidget";

export { FileWidget, fileWidgetConfig } from "./FileWidget";
export type { FileWidgetProps } from "./FileWidget";

export {
  ChatMessageWidget,
  chatMessageWidgetConfig,
} from "./ChatMessageWidget";
export type {
  ChatMessageWidgetProps,
  ChatMessageRole,
} from "./ChatMessageWidget";

// ============================================================================
// AGGREGATED CONFIGS (for catalog building)
// ============================================================================

import { headerWidgetConfig } from "./HeaderWidget";
import { dividerWidgetConfig } from "./DividerWidget";
import { textBlockWidgetConfig } from "./TextBlockWidget";
import { codeWidgetConfig } from "./CodeWidget";
import { imageWidgetConfig } from "./ImageWidget";
import { videoWidgetConfig } from "./VideoWidget";
import { audioWidgetConfig } from "./AudioWidget";
import { fileWidgetConfig } from "./FileWidget";
import { chatMessageWidgetConfig } from "./ChatMessageWidget";

export const blockWidgetConfigs = {
  Header: headerWidgetConfig,
  Divider: dividerWidgetConfig,
  TextBlock: textBlockWidgetConfig,
  Code: codeWidgetConfig,
  Image: imageWidgetConfig,
  Video: videoWidgetConfig,
  Audio: audioWidgetConfig,
  File: fileWidgetConfig,
  ChatMessage: chatMessageWidgetConfig,
} as const;

// ============================================================================
// AGGREGATED REGISTRY (for rendering)
// ============================================================================

import { HeaderWidget } from "./HeaderWidget";
import { DividerWidget } from "./DividerWidget";
import { TextBlockWidget } from "./TextBlockWidget";
import { CodeWidget } from "./CodeWidget";
import { ImageWidget } from "./ImageWidget";
import { VideoWidget } from "./VideoWidget";
import { AudioWidget } from "./AudioWidget";
import { FileWidget } from "./FileWidget";
import { ChatMessageWidget } from "./ChatMessageWidget";

function createHeaderRenderer({ props }: ComponentRenderProps) {
  return HeaderWidget({
    text: String(props.text ?? ""),
    level: props.level as 1 | 2 | 3 | undefined,
  });
}

function createDividerRenderer() {
  return DividerWidget({});
}

function createTextBlockRenderer({ props }: ComponentRenderProps) {
  const content = props.content;

  // DEBUG: Log when TextBlock receives non-string content
  if (isJsonValue(content)) {
    console.debug(
      "[TextBlock] Received JSON content - consider using Code widget instead:",
      { content, label: props.label },
    );
  }

  return TextBlockWidget({
    content: toDisplayString(content),
    label: props.label as string | null | undefined,
    maxLines: props.maxLines as number | null | undefined,
    expandable: props.expandable as boolean | null | undefined,
  });
}

function createCodeRenderer({ props }: ComponentRenderProps) {
  const content = props.content;
  const isJson = isJsonValue(content);

  // Auto-detect language if not specified and content is JSON
  const language = props.language ?? (isJson ? "json" : null);

  return CodeWidget({
    content: toDisplayString(content),
    language: language as string | null | undefined,
    label: props.label as string | null | undefined,
    wrap: Boolean(props.wrap),
    showLineNumbers: props.showLineNumbers !== false,
    showCopy: props.showCopy !== false,
  });
}

function createImageRenderer({ props }: ComponentRenderProps) {
  return ImageWidget({
    src: String(props.src ?? ""),
    alt: props.alt as string | null | undefined,
    label: props.label as string | null | undefined,
    tag: props.tag as string | null | undefined,
  });
}

function createVideoRenderer({ props }: ComponentRenderProps) {
  return VideoWidget({
    src: String(props.src ?? ""),
    label: props.label as string | null | undefined,
    tag: props.tag as string | null | undefined,
    controls: props.controls !== false,
  });
}

function createAudioRenderer({ props }: ComponentRenderProps) {
  return AudioWidget({
    src: String(props.src ?? ""),
    label: props.label as string | null | undefined,
    tag: props.tag as string | null | undefined,
    controls: props.controls !== false,
  });
}

function createFileRenderer({ props }: ComponentRenderProps) {
  return FileWidget({
    url: String(props.url ?? ""),
    filename: props.filename as string | null | undefined,
    label: props.label as string | null | undefined,
    type: props.type as string | null | undefined,
  });
}

function createChatMessageRenderer({ props }: ComponentRenderProps) {
  return ChatMessageWidget({
    content: String(props.content ?? ""),
    role: (props.role as "user" | "assistant") ?? "assistant",
  });
}

export const blockWidgetRegistry: ComponentRegistry = {
  Header: createHeaderRenderer,
  Divider: createDividerRenderer,
  TextBlock: createTextBlockRenderer,
  Code: createCodeRenderer,
  Image: createImageRenderer,
  Video: createVideoRenderer,
  Audio: createAudioRenderer,
  File: createFileRenderer,
  ChatMessage: createChatMessageRenderer,
};
