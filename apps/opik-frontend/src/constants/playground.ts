import { PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";

type PROVIDER_MODELS_TYPE = {
  [key in PROVIDER_TYPE]: {
    value: PROVIDER_MODEL_TYPE;
    label: string;
  }[];
};

export const PROVIDER_MODELS: PROVIDER_MODELS_TYPE = {
  [PROVIDER_TYPE.OPEN_AI]: [
    // GPT-4.0 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O,
      label: "GPT 4o",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      label: "GPT 4o Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI_2024_07_18,
      label: "GPT 4o Mini 2024-07-18",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_2024_11_20,
      label: "GPT 4o 2024-11-20",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_2024_08_06,
      label: "GPT 4o 2024-08-06",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_2024_05_13,
      label: "GPT 4o 2024-05-13",
    },

    // GPT-4 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO,
      label: "GPT 4 Turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4,
      label: "GPT 4",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO_PREVIEW,
      label: "GPT 4 Turbo Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO_2024_04_09,
      label: "GPT 4 Turbo 2024-04-09",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_1106_PREVIEW,
      label: "GPT 4 1106 Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_0613,
      label: "GPT 4 0613",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_0125_PREVIEW,
      label: "GPT 4 0125 Preview",
    },

    // GPT-3.5 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO,
      label: "GPT 3.5 Turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_16K,
      label: "GPT 3.5 Turbo 16k",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_1106,
      label: "GPT 3.5 Turbo 1106",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_0125,
      label: "GPT 3.5 Turbo 0125",
    },

    // Reasoning Models
    {
      value: PROVIDER_MODEL_TYPE.O1_PREVIEW,
      label: "O1 Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.O1_MINI,
      label: "O1 Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.O1_MINI_2024_09_12,
      label: "O1 Mini 2024-09-12",
    },
    {
      value: PROVIDER_MODEL_TYPE.O1_PREVIEW_2024_09_12,
      label: "O1 Preview 2024-09-12",
    },

    // Other Models
    {
      value: PROVIDER_MODEL_TYPE.CHATGPT_4O_LATEST,
      label: "ChatGPT 4o Latest",
    },
  ],
};

export const DEFAULT_OPEN_AI_CONFIGS = {
  TEMPERATURE: 0,
  MAX_COMPLETION_TOKENS: 1024,
  TOP_P: 1,
  FREQUENCY_PENALTY: 0,
  PRESENCE_PENALTY: 0,
};
