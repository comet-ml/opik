import { ReactNode, ComponentPropsWithoutRef } from "react";
import * as AccordionPrimitive from "@radix-ui/react-accordion";

export type MessageRole =
  | "system"
  | "user"
  | "assistant"
  | "tool"
  | "function"
  | "human"
  | "ai";

export type PrettyLLMMessageContainerProps = ComponentPropsWithoutRef<
  typeof AccordionPrimitive.Root
>;

export interface PrettyLLMMessageRootProps {
  value: string;
  children: ReactNode;
  className?: string;
}

export interface PrettyLLMMessageHeaderProps {
  role: MessageRole;
  label?: ReactNode;
  className?: string;
}

export interface PrettyLLMMessageContentProps {
  children: ReactNode;
  className?: string;
}

export interface PrettyLLMMessageTextBlockProps {
  children: string | ReactNode;
  role?: MessageRole;
  showMoreButton?: boolean;
  className?: string;
}

export interface PrettyLLMMessageImageBlockProps {
  images: Array<{ url: string; name: string }>;
  className?: string;
}

export interface PrettyLLMMessageVideoBlockProps {
  videos: Array<{ url: string; name: string }>;
  className?: string;
}

export interface PrettyLLMMessageCodeBlockProps {
  code: string;
  label?: string;
  className?: string;
}

export interface PrettyLLMMessageAudioPlayerBlockProps {
  audios: Array<{ url: string; name: string }>;
  className?: string;
}

export interface PrettyLLMMessageFooterProps {
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  finishReason?: string;
  className?: string;
}

export interface PrettyLLMMessageFinishReasonProps {
  finishReason?: string;
  className?: string;
}

export interface PrettyLLMMessageUsageProps {
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  className?: string;
}
