import OpenAIIcon from "@/icons/integrations/openai.svg?react";
import { PLAYGROUND_MODEL, PLAYGROUND_PROVIDER } from "@/types/playground";

// @ToDo: remove it
export const OPENAI_API_KEY = "OPENAI_API_KEY";

export const PLAYGROUND_PROVIDERS = {
  [PLAYGROUND_PROVIDER.OpenAI]: {
    title: "Open AI",
    value: PLAYGROUND_PROVIDER.OpenAI,
    icon: OpenAIIcon,
  },
};

export const PLAYGROUND_MODELS = {
  [PLAYGROUND_PROVIDER.OpenAI]: [
    // GPT-4.0 Models
    {
      value: PLAYGROUND_MODEL.GPT_4O,
      label: "GPT 4o",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4O_MINI,
      label: "GPT 4o Mini",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4O_MINI_2024_07_18,
      label: "GPT 4o Mini 2024-07-18",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4O_2024_11_20,
      label: "GPT 4o 2024-11-20",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4O_2024_08_06,
      label: "GPT 4o 2024-08-06",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4O_2024_05_13,
      label: "GPT 4o 2024-05-13",
    },

    // GPT-4 Models
    {
      value: PLAYGROUND_MODEL.GPT_4_TURBO,
      label: "GPT 4 Turbo",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4,
      label: "GPT 4",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4_TURBO_PREVIEW,
      label: "GPT 4 Turbo Preview",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4_TURBO_2024_04_09,
      label: "GPT 4 Turbo 2024-04-09",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4_1106_PREVIEW,
      label: "GPT 4 1106 Preview",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4_0613,
      label: "GPT 4 0613",
    },
    {
      value: PLAYGROUND_MODEL.GPT_4_0125_PREVIEW,
      label: "GPT 4 0125 Preview",
    },

    // GPT-3.5 Models
    {
      value: PLAYGROUND_MODEL.GPT_3_5_TURBO,
      label: "GPT 3.5 Turbo",
    },
    {
      value: PLAYGROUND_MODEL.GPT_3_5_TURBO_16K,
      label: "GPT 3.5 Turbo 16k",
    },
    {
      value: PLAYGROUND_MODEL.GPT_3_5_TURBO_1106,
      label: "GPT 3.5 Turbo 1106",
    },
    {
      value: PLAYGROUND_MODEL.GPT_3_5_TURBO_0125,
      label: "GPT 3.5 Turbo 0125",
    },

    // Reasoning Models
    {
      value: PLAYGROUND_MODEL.O1_PREVIEW,
      label: "o1 Preview",
    },
    {
      value: PLAYGROUND_MODEL.O1_MINI,
      label: "o1 Mini",
    },
    {
      value: PLAYGROUND_MODEL.O1_MINI_2024_09_12,
      label: "o1 Mini 2024-09-12",
    },
    {
      value: PLAYGROUND_MODEL.O1_PREVIEW_2024_09_12,
      label: "o1 Preview 2024-09-12",
    },

    // Other Models
    {
      value: PLAYGROUND_MODEL.CHATGPT_4O_LATEST,
      label: "ChatGPT 4o Latest",
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
