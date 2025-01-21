import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import AnthropicIcon from "@/icons/integrations/anthropic.svg?react";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

type IconType = typeof OpenAIIcon;

export type PROVIDER_OPTION_TYPE = {
  label: string;
  value: PROVIDER_TYPE;
  icon: IconType;
  apiKeyName: string;
  apiKeyURL: string;
  defaultModel: PROVIDER_MODEL_TYPE;
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
    defaultModel: PROVIDER_MODEL_TYPE.GPT_4O,
  },
  [PROVIDER_TYPE.ANTHROPIC]: {
    label: "Anthropic",
    value: PROVIDER_TYPE.ANTHROPIC,
    icon: AnthropicIcon,
    apiKeyName: "ANTHROPIC_API_KEY",
    apiKeyURL: "https://console.anthropic.com/settings/keys",
    defaultModel: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_LATEST,
  },
};

export const PROVIDERS_OPTIONS = Object.values(PROVIDERS);
