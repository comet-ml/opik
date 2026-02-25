import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import AnthropicIcon from "@/icons/integrations/anthropic.svg?react";
import OpenRouterIcon from "@/icons/integrations/open_router.svg?react";
import GeminiIcon from "@/icons/integrations/gemini.svg?react";
import VertexAIIcon from "@/icons/integrations/vertex_ai.svg?react";
import BedrockIcon from "@/icons/integrations/bedrock.svg?react";
import CustomIcon from "@/icons/integrations/custom.svg?react";
import OpikIcon from "@/icons/integrations/opik.svg?react";
import OllamaIcon from "@/icons/integrations/ollama.svg?react";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { FeatureToggleKeys } from "@/types/feature-toggles";

export type IconType = typeof OpenAIIcon;

export type PROVIDER_OPTION_TYPE = {
  label: string;
  value: PROVIDER_TYPE;
  icon: IconType;
  apiKeyName: string;
  defaultModel: PROVIDER_MODEL_TYPE | "";
  description?: string;
  apiKeyURL?: string;
  defaultUrl?: string;
  /** If true, this provider is system-managed and users cannot configure it */
  readOnly?: boolean;
};

type PROVIDERS_TYPE = {
  [key in PROVIDER_TYPE]: PROVIDER_OPTION_TYPE;
};

export const PROVIDERS: PROVIDERS_TYPE = {
  [PROVIDER_TYPE.OPIK_FREE]: {
    label: "Opik",
    value: PROVIDER_TYPE.OPIK_FREE,
    icon: OpikIcon,
    apiKeyName: "OPIK_FREE_MODEL_API_KEY",
    defaultModel: PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
    description: "Free model provided by Opik - no API key required",
    readOnly: true,
  },
  [PROVIDER_TYPE.OPEN_AI]: {
    label: "OpenAI",
    value: PROVIDER_TYPE.OPEN_AI,
    icon: OpenAIIcon,
    apiKeyName: "OPENAI_API_KEY",
    apiKeyURL: "https://platform.openai.com/account/api-keys",
    defaultModel: PROVIDER_MODEL_TYPE.GPT_5_2,
  },
  [PROVIDER_TYPE.ANTHROPIC]: {
    label: "Anthropic",
    value: PROVIDER_TYPE.ANTHROPIC,
    icon: AnthropicIcon,
    apiKeyName: "ANTHROPIC_API_KEY",
    apiKeyURL: "https://console.anthropic.com/settings/keys",
    defaultModel: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
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
    defaultModel: PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
  },
  [PROVIDER_TYPE.VERTEX_AI]: {
    label: "Vertex AI",
    value: PROVIDER_TYPE.VERTEX_AI,
    icon: VertexAIIcon,
    apiKeyName: "VERTEX_API_KEY",
    defaultModel: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17,
  },
  [PROVIDER_TYPE.BEDROCK]: {
    label: "Bedrock",
    value: PROVIDER_TYPE.BEDROCK,
    icon: BedrockIcon,
    apiKeyName: "BEDROCK_API_KEY",
    defaultModel: "",
  },
  [PROVIDER_TYPE.OLLAMA]: {
    label: "Ollama",
    value: PROVIDER_TYPE.OLLAMA,
    icon: OllamaIcon,
    apiKeyName: "OLLAMA_API_KEY",
    defaultModel: "",
    description:
      "Run open-source LLMs locally with Ollama. Connect to your local or cloud Ollama instance.",
    apiKeyURL: "https://github.com/ollama/ollama",
    defaultUrl: "http://localhost:11434/v1",
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

// Mapping between provider types and their feature toggle keys
// OPIK_FREE is excluded - its visibility is controlled by freeModel.enabled backend config
export const PROVIDER_FEATURE_TOGGLE_MAP: Record<
  Exclude<PROVIDER_TYPE, PROVIDER_TYPE.OPIK_FREE>,
  FeatureToggleKeys
> = {
  [PROVIDER_TYPE.OPEN_AI]: FeatureToggleKeys.OPENAI_PROVIDER_ENABLED,
  [PROVIDER_TYPE.ANTHROPIC]: FeatureToggleKeys.ANTHROPIC_PROVIDER_ENABLED,
  [PROVIDER_TYPE.GEMINI]: FeatureToggleKeys.GEMINI_PROVIDER_ENABLED,
  [PROVIDER_TYPE.OPEN_ROUTER]: FeatureToggleKeys.OPENROUTER_PROVIDER_ENABLED,
  [PROVIDER_TYPE.VERTEX_AI]: FeatureToggleKeys.VERTEXAI_PROVIDER_ENABLED,
  [PROVIDER_TYPE.BEDROCK]: FeatureToggleKeys.BEDROCK_PROVIDER_ENABLED,
  [PROVIDER_TYPE.OLLAMA]: FeatureToggleKeys.OLLAMA_PROVIDER_ENABLED,
  [PROVIDER_TYPE.CUSTOM]: FeatureToggleKeys.CUSTOMLLM_PROVIDER_ENABLED,
};

export const LEGACY_CUSTOM_PROVIDER_NAME = "default";
