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

// Provider types
export type LLMProvider = "openai" | "anthropic" | "google";

// Detection result
export interface LLMProviderDetectionResult {
  supported: boolean;
  empty?: boolean;
  provider?: LLMProvider;
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

// Provider detector contract
export type ProviderDetector = (
  data: unknown,
  prettifyConfig?: { fieldType?: "input" | "output" },
) => boolean;

// Provider mapper contract
export type ProviderMapper = (
  data: unknown,
  prettifyConfig?: { fieldType?: "input" | "output" },
) => LLMMapperResult;

// Provider interface
export interface LLMProviderImplementation {
  name: LLMProvider;
  detector: ProviderDetector;
  mapper: ProviderMapper;
}
