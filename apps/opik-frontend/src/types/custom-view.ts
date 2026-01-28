import { z } from "zod";
import { Trace, Thread } from "./traces";

/**
 * Context type for custom view - determines what data is being visualized
 */
export type ContextType = "trace" | "thread";

/**
 * Enriched thread with all traces (messages) included
 */
export interface EnrichedThread extends Thread {
  traces: Trace[];
}

/**
 * Union type for context data - can be either Trace or EnrichedThread
 */
export type ContextData = Trace | EnrichedThread;

// ============================================================================
// LEGACY SCHEMA TYPES (for backward compatibility with SMEFlowPage)
// ============================================================================

/**
 * Widget size options for layout
 */
export enum WidgetSize {
  SMALL = "small",
  MEDIUM = "medium",
  LARGE = "large",
  FULL = "full",
}

/**
 * Widget type options
 */
export type WidgetType =
  | "text"
  | "number"
  | "boolean"
  | "code"
  | "link"
  | "image"
  | "video"
  | "audio"
  | "pdf"
  | "file";

/**
 * Widget configuration for the legacy schema-based system
 */
export interface WidgetConfig {
  path: string;
  uiWidget: WidgetType;
  label: string;
  size: WidgetSize;
}

/**
 * Legacy custom view schema structure
 */
export interface CustomViewSchema {
  responseSummary: string;
  widgets: WidgetConfig[];
}

/**
 * Zod schema for widget configuration
 */
const widgetConfigSchema = z.object({
  path: z.string().describe("Data path using dot notation"),
  uiWidget: z
    .enum([
      "text",
      "number",
      "boolean",
      "code",
      "link",
      "image",
      "video",
      "audio",
      "pdf",
      "file",
    ])
    .describe("Widget type to use"),
  label: z.string().describe("Display label for the widget"),
  size: z.enum(["small", "medium", "large", "full"]).describe("Widget size"),
});

/**
 * Zod schema for the legacy custom view schema
 */
export const customViewZodSchema = z.object({
  responseSummary: z
    .string()
    .describe("Brief explanation of the selected fields"),
  widgets: z
    .array(widgetConfigSchema)
    .describe("Array of widget configurations"),
});
