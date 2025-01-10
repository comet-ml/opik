import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLMPPromptTemplate,
  LLM_SCHEMA_TYPE,
} from "@/types/llm";

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
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_1106,
      label: "GPT 3.5 Turbo 1106",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_0125,
      label: "GPT 3.5 Turbo 0125",
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

export const LLM_PROMPT_TEMPLATES: LLMPPromptTemplate[] = [
  {
    label: "Custom LLM-as-judge",
    description: "Use our template editor to write your own code",
    value: LLM_JUDGE.custom,
    messages: [
      {
        role: LLM_MESSAGE_ROLE.user,
        content:
          "You are messaging a chat bot response to a user’s query based on a set of criteria. Here is the data:\n" +
          "[BEGIN DATA]\n" +
          "...\n" +
          "[User query]: {{query}}\n" +
          "...\n" +
          "[Response]: {{response}}\n" +
          "...\n" +
          "[END DATA]",
      },
    ],
    variables: {
      query: "",
      response: "",
    },
    schema: [
      {
        name: "Hallucination",
        description: "Provide the score:",
        type: LLM_SCHEMA_TYPE.INTEGER,
      },
    ],
  },
  {
    label: "Hallucination",
    description: "Check if the output contains any hallucinations",
    value: LLM_JUDGE.hallucination,
    messages: [
      {
        role: LLM_MESSAGE_ROLE.user,
        content:
          "Summary: {{summary}}\n" +
          "Instruction: {{instruction}}\n" +
          "\n" +
          "Evaluate the summary based on the following criteria:\n" +
          "1. Relevance (1-5): How well does the summary address the given instruction?\n" +
          "2. Conciseness (1-5): How concise is the summary while retaining key information?\n" +
          "3. Technical Accuracy (1-5): How accurately does the summary convey technical details?",
      },
    ],
    variables: {
      summary: "",
      instruction: "",
    },
    schema: [
      {
        name: "Hallucination",
        description: "Provide the score:",
        type: LLM_SCHEMA_TYPE.INTEGER,
      },
    ],
  },
  {
    label: "Moderation",
    description: "Check if the output contains any harmful content",
    value: LLM_JUDGE.moderation,
    messages: [
      {
        role: LLM_MESSAGE_ROLE.user,
        content:
          "Summary: {{summary}}\n" +
          "Instruction: {{instruction}}\n" +
          "\n" +
          "Evaluate the summary based on the following criteria:\n" +
          "1. Relevance (1-5): How well does the summary address the given instruction?\n" +
          "2. Conciseness (1-5): How concise is the summary while retaining key information?\n" +
          "3. Technical Accuracy (1-5): How accurately does the summary convey technical details?",
      },
    ],
    variables: {
      summary: "",
      instruction: "",
    },
    schema: [
      {
        name: "Hallucination",
        description: "Provide the score:",
        type: LLM_SCHEMA_TYPE.INTEGER,
      },
    ],
  },
  {
    label: "AnswerRelevance",
    description: "Check if the output is relevant to the question",
    value: LLM_JUDGE.answer_relevance,
    messages: [
      {
        role: LLM_MESSAGE_ROLE.user,
        content:
          "Summary: {{summary}}\n" +
          "Instruction: {{instruction}}\n" +
          "\n" +
          "Evaluate the summary based on the following criteria:\n" +
          "1. Relevance (1-5): How well does the summary address the given instruction?\n" +
          "2. Conciseness (1-5): How concise is the summary while retaining key information?\n" +
          "3. Technical Accuracy (1-5): How accurately does the summary convey technical details?",
      },
    ],
    variables: {
      summary: "",
      instruction: "",
    },
    schema: [
      {
        name: "Hallucination",
        description: "Provide the score:",
        type: LLM_SCHEMA_TYPE.INTEGER,
      },
    ],
  },
  {
    label: "ContextPrecision",
    description: "Check if the output contains any hallucinations",
    value: LLM_JUDGE.context_precision,
    messages: [
      {
        role: LLM_MESSAGE_ROLE.user,
        content:
          "Summary: {{summary}}\n" +
          "Instruction: {{instruction}}\n" +
          "\n" +
          "Evaluate the summary based on the following criteria:\n" +
          "1. Relevance (1-5): How well does the summary address the given instruction?\n" +
          "2. Conciseness (1-5): How concise is the summary while retaining key information?\n" +
          "3. Technical Accuracy (1-5): How accurately does the summary convey technical details?",
      },
    ],
    variables: {
      summary: "",
      instruction: "",
    },
    schema: [
      {
        name: "Hallucination",
        description: "Provide the score:",
        type: LLM_SCHEMA_TYPE.INTEGER,
      },
    ],
  },
];
