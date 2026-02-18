import { ComponentType } from "react";
import {
  PrettyLLMMessageTextBlockProps,
  PrettyLLMMessageImageBlockProps,
  PrettyLLMMessageVideoBlockProps,
  PrettyLLMMessageAudioPlayerBlockProps,
  PrettyLLMMessageCodeBlockProps,
  PrettyLLMMessageUsageProps,
  MessageRole,
} from "@/components/shared/PrettyLLMMessage/types";

// Format types
export type LLMMessageFormat = "openai" | "langchain" | "anthropic" | "google";

// Detection result
export interface LLMMessageFormatDetectionResult {
  supported: boolean;
  empty?: boolean;
  format?: LLMMessageFormat;
  confidence?: "high" | "medium" | "low";
}

// Block descriptor with component reference (Component-as-Data pattern)
export type LLMBlockDescriptor =
  | {
      blockType: "text";
      component: ComponentType<PrettyLLMMessageTextBlockProps>;
      props: PrettyLLMMessageTextBlockProps;
    }
  | {
      blockType: "image";
      component: ComponentType<PrettyLLMMessageImageBlockProps>;
      props: PrettyLLMMessageImageBlockProps;
    }
  | {
      blockType: "video";
      component: ComponentType<PrettyLLMMessageVideoBlockProps>;
      props: PrettyLLMMessageVideoBlockProps;
    }
  | {
      blockType: "audio";
      component: ComponentType<PrettyLLMMessageAudioPlayerBlockProps>;
      props: PrettyLLMMessageAudioPlayerBlockProps;
    }
  | {
      blockType: "code";
      component: ComponentType<PrettyLLMMessageCodeBlockProps>;
      props: PrettyLLMMessageCodeBlockProps;
    };

// Message descriptor
export interface LLMMessageDescriptor {
  id: string;
  role: MessageRole;
  label?: string;
  blocks: LLMBlockDescriptor[];
  finishReason?: string;
}

// Mapper result with messages and shared usage
export interface LLMMapperResult {
  messages: LLMMessageDescriptor[];
  usage?: PrettyLLMMessageUsageProps["usage"];
}

// Format detector contract
export type FormatDetector = (
  data: unknown,
  prettifyConfig?: { fieldType?: "input" | "output" },
) => boolean;

// Format mapper contract
export type FormatMapper = (
  data: unknown,
  prettifyConfig?: { fieldType?: "input" | "output" },
) => LLMMapperResult;

// Format combiner contract
export type FormatCombiner = (
  input: { raw: unknown; mapped: LLMMapperResult },
  output: { raw: unknown; mapped: LLMMapperResult },
) => LLMMapperResult;

// Format interface
export interface LLMMessageFormatImplementation {
  name: LLMMessageFormat;
  detector: FormatDetector;
  mapper: FormatMapper;
  combiner?: FormatCombiner;
}
