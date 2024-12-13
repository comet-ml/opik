import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import { PLAYGROUND_MODEL, PLAYGROUND_PROVIDER } from "@/types/playground";

export const PLAYGROUND_PROVIDERS = {
  [PLAYGROUND_PROVIDER.OpenAI]: {
    title: "Open AI",
    value: PLAYGROUND_PROVIDER.OpenAI,
    icon: OpenAIIcon,
  },
};

// ALEX
export const PLAYGROUND_MODELS = {
  [PLAYGROUND_PROVIDER.OpenAI]: [
    {
      value: PLAYGROUND_MODEL["gpt-4o"],
      label: "GPT 4o",
    },
    {
      value: PLAYGROUND_MODEL["gpt-4o-mini"],
      label: "GPT 4o mini",
    },
    {
      value: PLAYGROUND_MODEL["gpt-4o-2024-11-20"],
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
