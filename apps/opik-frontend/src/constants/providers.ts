import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import AnthropicIcon from "@/icons/integrations/anthropic.svg?react";
import OpenRouterIcon from "@/icons/integrations/open_router.svg?react";
import OllamaIcon from "@/icons/integrations/ollama.svg?react";
import GeminiIcon from "@/icons/integrations/gemini.svg?react";
import VertexAIIcon from "@/icons/integrations/vertex_ai.svg?react";

import {
  PROVIDER_LOCATION_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

type IconType = typeof OpenAIIcon;

type PROVIDER_OPTION_TYPE = {
  label: string;
  value: PROVIDER_TYPE;
  icon: IconType;
  apiKeyName: string;
  defaultModel: PROVIDER_MODEL_TYPE | "";
  description?: string;
};

export type CLOUD_PROVIDER_OPTION_TYPE = PROVIDER_OPTION_TYPE & {
  locationType: PROVIDER_LOCATION_TYPE.cloud;
  apiKeyURL?: string;
};

export type LOCAL_PROVIDER_OPTION_TYPE = PROVIDER_OPTION_TYPE & {
  locationType: PROVIDER_LOCATION_TYPE.local;
  lsKey: string;
};

type PROVIDERS_TYPE = {
  [key in PROVIDER_TYPE]:
    | CLOUD_PROVIDER_OPTION_TYPE
    | LOCAL_PROVIDER_OPTION_TYPE;
};

export const OLLAMA_LS_KEY = "provider_ollama";

export const PROVIDERS: PROVIDERS_TYPE = {
  [PROVIDER_TYPE.OPEN_AI]: {
    label: "OpenAI",
    value: PROVIDER_TYPE.OPEN_AI,
    icon: OpenAIIcon,
    apiKeyName: "OPENAI_API_KEY",
    apiKeyURL: "https://platform.openai.com/account/api-keys",
    defaultModel: PROVIDER_MODEL_TYPE.GPT_4O,
    locationType: PROVIDER_LOCATION_TYPE.cloud,
  },
  [PROVIDER_TYPE.ANTHROPIC]: {
    label: "Anthropic",
    value: PROVIDER_TYPE.ANTHROPIC,
    icon: AnthropicIcon,
    apiKeyName: "ANTHROPIC_API_KEY",
    apiKeyURL: "https://console.anthropic.com/settings/keys",
    defaultModel: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_LATEST,
    locationType: PROVIDER_LOCATION_TYPE.cloud,
  },
  [PROVIDER_TYPE.OPEN_ROUTER]: {
    label: "OpenRouter",
    value: PROVIDER_TYPE.OPEN_ROUTER,
    icon: OpenRouterIcon,
    apiKeyName: "OPENROUTER_API_KEY",
    apiKeyURL: "https://openrouter.ai/keys",
    defaultModel: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
    locationType: PROVIDER_LOCATION_TYPE.cloud,
  },
  [PROVIDER_TYPE.OLLAMA]: {
    label: "Ollama (Experimental)",
    value: PROVIDER_TYPE.OLLAMA,
    icon: OllamaIcon,
    apiKeyName: "OLLAMA_LOCAL_EXPERIMENTAL",
    description:
      "All configuration for this provider is saved locally, and will not \nbe accessible in different browsers",
    locationType: PROVIDER_LOCATION_TYPE.local,
    lsKey: OLLAMA_LS_KEY,
    defaultModel: "",
  },
  [PROVIDER_TYPE.GEMINI]: {
    label: "Gemini",
    value: PROVIDER_TYPE.GEMINI,
    icon: GeminiIcon,
    apiKeyName: "GEMINI_API_KEY",
    apiKeyURL: "https://aistudio.google.com/apikey",
    defaultModel: PROVIDER_MODEL_TYPE.GEMINI_1_5_FLASH,
    locationType: PROVIDER_LOCATION_TYPE.cloud,
  },
  [PROVIDER_TYPE.VERTEX_AI]: {
    label: "Vertex AI",
    value: PROVIDER_TYPE.VERTEX_AI,
    icon: VertexAIIcon,
    apiKeyName: "VERTEX_API_KEY",
    defaultModel: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17,
    locationType: PROVIDER_LOCATION_TYPE.cloud,
  },
};

export const PROVIDERS_OPTIONS = Object.values(PROVIDERS);
