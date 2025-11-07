import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import AnthropicIcon from "@/icons/integrations/anthropic.svg?react";
import OpenRouterIcon from "@/icons/integrations/open_router.svg?react";
import GeminiIcon from "@/icons/integrations/gemini.svg?react";
import VertexAIIcon from "@/icons/integrations/vertex_ai.svg?react";
import CustomIcon from "@/icons/integrations/custom.svg?react";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

export type IconType = typeof OpenAIIcon;

export type PROVIDER_OPTION_TYPE = {
  label: string;
  value: PROVIDER_TYPE;
  icon: IconType;
  apiKeyName: string;
  defaultModel: PROVIDER_MODEL_TYPE | "";
  description?: string;
  apiKeyURL?: string;
};

type PROVIDERS_TYPE = {
  [key in PROVIDER_TYPE]: PROVIDER_OPTION_TYPE;
};

export const PROVIDERS: PROVIDERS_TYPE = {
  [PROVIDER_TYPE.OPEN_AI]: {
    label: "OpenAI",
    value: PROVIDER_TYPE.OPEN_AI,
    icon: OpenAIIcon,
    apiKeyName: "OPENAI_API_KEY",
    apiKeyURL: "https://platform.openai.com/account/api-keys",
    defaultModel: PROVIDER_MODEL_TYPE.GPT_5,
  },
  [PROVIDER_TYPE.ANTHROPIC]: {
    label: "Anthropic",
    value: PROVIDER_TYPE.ANTHROPIC,
    icon: AnthropicIcon,
    apiKeyName: "ANTHROPIC_API_KEY",
    apiKeyURL: "https://console.anthropic.com/settings/keys",
    defaultModel: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5,
  },
  [PROVIDER_TYPE.OPEN_ROUTER]: {
    label: "OpenRouter",
    value: PROVIDER_TYPE.OPEN_ROUTER,
    icon: OpenRouterIcon,
    apiKeyName: "OPENROUTER_API_KEY",
    apiKeyURL: "https://openrouter.ai/keys",
    defaultModel: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
  },
  [PROVIDER_TYPE.GEMINI]: {
    label: "Gemini",
    value: PROVIDER_TYPE.GEMINI,
    icon: GeminiIcon,
    apiKeyName: "GEMINI_API_KEY",
    apiKeyURL: "https://aistudio.google.com/apikey",
    defaultModel: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
  },
  [PROVIDER_TYPE.VERTEX_AI]: {
    label: "Vertex AI",
    value: PROVIDER_TYPE.VERTEX_AI,
    icon: VertexAIIcon,
    apiKeyName: "VERTEX_API_KEY",
    defaultModel: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17,
  },
  [PROVIDER_TYPE.CUSTOM]: {
    label: "vLLM / Custom provider",
    value: PROVIDER_TYPE.CUSTOM,
    icon: CustomIcon,
    apiKeyName: "CUSTOM_PROVIDER_API_KEY",
    defaultModel: "",
    description:
      "You can configure any OpenAI API-compatible provider (vLLM, \nOllama, etc.) using the standardized OpenAI API interface.",
  },
};

export const PROVIDERS_OPTIONS = Object.values(PROVIDERS);

export const CUSTOM_PROVIDER_MODEL_PREFIX = "custom-llm";

export const LEGACY_CUSTOM_PROVIDER_NAME = "default";
