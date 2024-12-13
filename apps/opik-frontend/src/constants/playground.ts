import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import {
  PLAYGROUND_MODEL_TYPE,
  PLAYGROUND_PROVIDERS_TYPES,
} from "@/types/playgroundPrompts";

export const PLAYGROUND_PROVIDERS = {
  [PLAYGROUND_PROVIDERS_TYPES.OpenAI]: {
    title: "Open AI",
    value: PLAYGROUND_PROVIDERS_TYPES.OpenAI,
    icon: OpenAIIcon,
  },
};

// ALEX
export const PLAYGROUND_MODELS = {
  [PLAYGROUND_PROVIDERS_TYPES.OpenAI]: [
    {
      value: PLAYGROUND_MODEL_TYPE["gpt-4o"],
      label: "GPT 4o",
    },
    {
      value: PLAYGROUND_MODEL_TYPE["gpt-4o-mini"],
      label: "GPT 4o mini",
    },
    {
      value: PLAYGROUND_MODEL_TYPE["gpt-4o-2024-11-20"],
      label: "GPT 4o-2024-11-20",
    },
  ],
};

export const DEFAULT_OPEN_AI_CONFIGS = {
  TEMPERATURE: 0,
  MAX_TOKENS: 1024,
  TOP_P: 1,
  STOP: "",
  FREQUENCY_PENALTY: 0,
  PRESENCE_PENALTY: 0,
};
