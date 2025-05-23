import { useCallback } from "react";
import first from "lodash/first";
import {
  PROVIDER_LOCATION_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_MODELS_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import useLocalAIProviderData from "@/hooks/useLocalAIProviderData";
import { getDefaultProviderKey } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";

export type ProviderResolver = (
  modelName?: PROVIDER_MODEL_TYPE | "",
) => PROVIDER_TYPE | "";

export type ModelResolver = (
  lastPickedModel: PROVIDER_MODEL_TYPE | "",
  setupProviders: PROVIDER_TYPE[],
  preferredProvider?: PROVIDER_TYPE | "",
) => PROVIDER_MODEL_TYPE | "";

export const PROVIDER_MODELS: PROVIDER_MODELS_TYPE = {
  [PROVIDER_TYPE.OPEN_AI]: [
    // GPT-4.0 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O,
      label: "GPT 4o",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      label: "GPT 4o Mini",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI_2024_07_18,
      label: "GPT 4o Mini 2024-07-18",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_2024_08_06,
      label: "GPT 4o 2024-08-06",
      structuredOutput: true,
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

  [PROVIDER_TYPE.ANTHROPIC]: [
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_20241022,
      label: "Claude 3.5 Sonnet 2024-10-22",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_HAIKU_20241022,
      label: "Claude 3.5 Haiku 2024-10-22",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_20240620,
      label: "Claude 3.5 Sonnet 2024-06-20",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_OPUS_20240229,
      label: "Claude 3 Opus 2024-02-29",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_SONNET_20240229,
      label: "Claude 3 Sonnet 2024-02-29",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_HAIKU_20240307,
      label: "Claude 3 Haiku 2024-03-07",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_LATEST,
      label: "Claude 3.5 Sonnet Latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_HAIKU_LATEST,
      label: "Claude 3.5 Haiku Latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_OPUS_LATEST,
      label: "Claude 3 Opus Latest",
    },
  ],

  [PROVIDER_TYPE.OPEN_ROUTER]: [
    {
      value: PROVIDER_MODEL_TYPE.AETHERWIING_MN_STARCANNON_12B,
      label: "aetherwiing/mn-starcannon-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.AI21_JAMBA_1_5_LARGE,
      label: "ai21/jamba-1-5-large",
    },
    {
      value: PROVIDER_MODEL_TYPE.AI21_JAMBA_1_5_MINI,
      label: "ai21/jamba-1-5-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.AI21_JAMBA_INSTRUCT,
      label: "ai21/jamba-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_1_0,
      label: "aion-labs/aion-1.0",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_1_0_MINI,
      label: "aion-labs/aion-1.0-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_RP_LLAMA_3_1_8B,
      label: "aion-labs/aion-rp-llama-3.1-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALLENAI_LLAMA_3_1_TULU_3_405B,
      label: "allenai/llama-3.1-tulu-3-405b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALLENAI_OLMO_7B_INSTRUCT,
      label: "allenai/olmo-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALPINDALE_GOLIATH_120B,
      label: "alpindale/goliath-120b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALPINDALE_MAGNUM_72B,
      label: "alpindale/magnum-72b",
    },
    {
      value: PROVIDER_MODEL_TYPE.AMAZON_NOVA_LITE_V1,
      label: "amazon/nova-lite-v1",
    },
    {
      value: PROVIDER_MODEL_TYPE.AMAZON_NOVA_MICRO_V1,
      label: "amazon/nova-micro-v1",
    },
    {
      value: PROVIDER_MODEL_TYPE.AMAZON_NOVA_PRO_V1,
      label: "amazon/nova-pro-v1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHRACITE_ORG_MAGNUM_V2_72B,
      label: "anthracite-org/magnum-v2-72b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHRACITE_ORG_MAGNUM_V4_72B,
      label: "anthracite-org/magnum-v4-72b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_1,
      label: "anthropic/claude-1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_1_2,
      label: "anthropic/claude-1.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_2,
      label: "anthropic/claude-2",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_2_0,
      label: "anthropic/claude-2.0",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_2_0_BETA,
      label: "anthropic/claude-2.0:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_2_1,
      label: "anthropic/claude-2.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_2_1_BETA,
      label: "anthropic/claude-2.1:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_2_BETA,
      label: "anthropic/claude-2:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_HAIKU,
      label: "anthropic/claude-3.5-haiku",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_HAIKU_20241022,
      label: "anthropic/claude-3.5-haiku-20241022",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_HAIKU_20241022_BETA,
      label: "anthropic/claude-3.5-haiku-20241022:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_HAIKU_BETA,
      label: "anthropic/claude-3.5-haiku:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_SONNET,
      label: "anthropic/claude-3.5-sonnet",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_SONNET_20240620,
      label: "anthropic/claude-3.5-sonnet-20240620",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_SONNET_20240620_BETA,
      label: "anthropic/claude-3.5-sonnet-20240620:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_SONNET_BETA,
      label: "anthropic/claude-3.5-sonnet:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_HAIKU,
      label: "anthropic/claude-3-haiku",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_HAIKU_BETA,
      label: "anthropic/claude-3-haiku:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_OPUS,
      label: "anthropic/claude-3-opus",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_OPUS_BETA,
      label: "anthropic/claude-3-opus:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_SONNET,
      label: "anthropic/claude-3-sonnet",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_SONNET_BETA,
      label: "anthropic/claude-3-sonnet:beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_INSTANT_1,
      label: "anthropic/claude-instant-1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_INSTANT_1_0,
      label: "anthropic/claude-instant-1.0",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_INSTANT_1_1,
      label: "anthropic/claude-instant-1.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.AUSTISM_CHRONOS_HERMES_13B,
      label: "austism/chronos-hermes-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.BIGCODE_STARCODER2_15B_INSTRUCT,
      label: "bigcode/starcoder2-15b-instruct",
    },
    {
      value:
        PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN3_0_MISTRAL_24B_FREE,
      label: "cognitivecomputations/dolphin3.0-mistral-24b:free",
    },
    {
      value:
        PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN3_0_R1_MISTRAL_24B_FREE,
      label: "cognitivecomputations/dolphin3.0-r1-mistral-24b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN_LLAMA_3_70B,
      label: "cognitivecomputations/dolphin-llama-3-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN_MIXTRAL_8X22B,
      label: "cognitivecomputations/dolphin-mixtral-8x22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN_MIXTRAL_8X7B,
      label: "cognitivecomputations/dolphin-mixtral-8x7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND,
      label: "cohere/command",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R,
      label: "cohere/command-r",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R7B_12_2024,
      label: "cohere/command-r7b-12-2024",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_03_2024,
      label: "cohere/command-r-03-2024",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_08_2024,
      label: "cohere/command-r-08-2024",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_PLUS,
      label: "cohere/command-r-plus",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_PLUS_04_2024,
      label: "cohere/command-r-plus-04-2024",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_PLUS_08_2024,
      label: "cohere/command-r-plus-08-2024",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.DATABRICKS_DBRX_INSTRUCT,
      label: "databricks/dbrx-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT,
      label: "deepseek/deepseek-chat",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT_FREE,
      label: "deepseek/deepseek-chat:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT_V2_5,
      label: "deepseek/deepseek-chat-v2.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CODER,
      label: "deepseek/deepseek-coder",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1,
      label: "deepseek/deepseek-r1",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_70B,
      label: "deepseek/deepseek-r1-distill-llama-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_70B_FREE,
      label: "deepseek/deepseek-r1-distill-llama-70b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_8B,
      label: "deepseek/deepseek-r1-distill-llama-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_14B,
      label: "deepseek/deepseek-r1-distill-qwen-14b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_1_5B,
      label: "deepseek/deepseek-r1-distill-qwen-1.5b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_32B,
      label: "deepseek/deepseek-r1-distill-qwen-32b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_FREE,
      label: "deepseek/deepseek-r1:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.EVA_UNIT_01_EVA_LLAMA_3_33_70B,
      label: "eva-unit-01/eva-llama-3.33-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.EVA_UNIT_01_EVA_QWEN_2_5_14B,
      label: "eva-unit-01/eva-qwen-2.5-14b",
    },
    {
      value: PROVIDER_MODEL_TYPE.EVA_UNIT_01_EVA_QWEN_2_5_32B,
      label: "eva-unit-01/eva-qwen-2.5-32b",
    },
    {
      value: PROVIDER_MODEL_TYPE.EVA_UNIT_01_EVA_QWEN_2_5_72B,
      label: "eva-unit-01/eva-qwen-2.5-72b",
    },
    {
      value: PROVIDER_MODEL_TYPE.FIREWORKS_FIRELLAVA_13B,
      label: "fireworks/firellava-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_001,
      label: "google/gemini-2.0-flash-001",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_EXP_FREE,
      label: "google/gemini-2.0-flash-exp:free",
      structuredOutput: true,
    },
    {
      value:
        PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_LITE_PREVIEW_02_05_FREE,
      label: "google/gemini-2.0-flash-lite-preview-02-05:free",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_THINKING_EXP_1219_FREE,
      label: "google/gemini-2.0-flash-thinking-exp-1219:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_THINKING_EXP_FREE,
      label: "google/gemini-2.0-flash-thinking-exp:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_PRO_EXP_02_05_FREE,
      label: "google/gemini-2.0-pro-exp-02-05:free",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_EXP_1114,
      label: "google/gemini-exp-1114",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_EXP_1121,
      label: "google/gemini-exp-1121",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_EXP_1206_FREE,
      label: "google/gemini-exp-1206:free",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_FLASH_1_5,
      label: "google/gemini-flash-1.5",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_FLASH_1_5_8B,
      label: "google/gemini-flash-1.5-8b",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_FLASH_1_5_8B_EXP,
      label: "google/gemini-flash-1.5-8b-exp",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_FLASH_1_5_EXP,
      label: "google/gemini-flash-1.5-exp",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_PRO,
      label: "google/gemini-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_PRO_1_5,
      label: "google/gemini-pro-1.5",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_PRO_1_5_EXP,
      label: "google/gemini-pro-1.5-exp",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_PRO_VISION,
      label: "google/gemini-pro-vision",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_27B_IT,
      label: "google/gemma-2-27b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_9B_IT,
      label: "google/gemma-2-9b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_9B_IT_FREE,
      label: "google/gemma-2-9b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_7B_IT,
      label: "google/gemma-7b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_LEARNLM_1_5_PRO_EXPERIMENTAL_FREE,
      label: "google/learnlm-1.5-pro-experimental:free",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_PALM_2_CHAT_BISON,
      label: "google/palm-2-chat-bison",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_PALM_2_CHAT_BISON_32K,
      label: "google/palm-2-chat-bison-32k",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_PALM_2_CODECHAT_BISON,
      label: "google/palm-2-codechat-bison",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_PALM_2_CODECHAT_BISON_32K,
      label: "google/palm-2-codechat-bison-32k",
    },
    {
      value: PROVIDER_MODEL_TYPE.GRYPHE_MYTHOMAX_L2_13B,
      label: "gryphe/mythomax-l2-13b",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GRYPHE_MYTHOMAX_L2_13B_FREE,
      label: "gryphe/mythomax-l2-13b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GRYPHE_MYTHOMIST_7B,
      label: "gryphe/mythomist-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.HUGGINGFACEH4_ZEPHYR_7B_BETA_FREE,
      label: "huggingfaceh4/zephyr-7b-beta:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.HUGGINGFACEH4_ZEPHYR_ORPO_141B_A35B,
      label: "huggingfaceh4/zephyr-orpo-141b-a35b",
    },
    {
      value: PROVIDER_MODEL_TYPE.INFERMATIC_MN_INFEROR_12B,
      label: "infermatic/mn-inferor-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.INFLATEBOT_MN_MAG_MELL_R1,
      label: "inflatebot/mn-mag-mell-r1",
    },
    {
      value: PROVIDER_MODEL_TYPE.INFLECTION_INFLECTION_3_PI,
      label: "inflection/inflection-3-pi",
    },
    {
      value: PROVIDER_MODEL_TYPE.INFLECTION_INFLECTION_3_PRODUCTIVITY,
      label: "inflection/inflection-3-productivity",
    },
    {
      value: PROVIDER_MODEL_TYPE.INTEL_NEURAL_CHAT_7B,
      label: "intel/neural-chat-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.JEBCARTER_PSYFIGHTER_13B,
      label: "jebcarter/psyfighter-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.JONDURBIN_AIROBOROS_L2_70B,
      label: "jondurbin/airoboros-l2-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.JONDURBIN_BAGEL_34B,
      label: "jondurbin/bagel-34b",
    },
    {
      value: PROVIDER_MODEL_TYPE.KOBOLDAI_PSYFIGHTER_13B_2,
      label: "koboldai/psyfighter-13b-2",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIQUID_LFM_3B,
      label: "liquid/lfm-3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIQUID_LFM_40B,
      label: "liquid/lfm-40b",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIQUID_LFM_7B,
      label: "liquid/lfm-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIUHAOTIAN_LLAVA_13B,
      label: "liuhaotian/llava-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIUHAOTIAN_LLAVA_YI_34B,
      label: "liuhaotian/llava-yi-34b",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIZPRECIATIOR_LZLV_70B_FP16_HF,
      label: "lizpreciatior/lzlv-70b-fp16-hf",
    },
    {
      value: PROVIDER_MODEL_TYPE.LYNN_SOLILOQUY_L3,
      label: "lynn/soliloquy-l3",
    },
    {
      value: PROVIDER_MODEL_TYPE.LYNN_SOLILOQUY_V3,
      label: "lynn/soliloquy-v3",
    },
    {
      value: PROVIDER_MODEL_TYPE.MANCER_WEAVER,
      label: "mancer/weaver",
    },
    {
      value: PROVIDER_MODEL_TYPE.MATTSHUMER_REFLECTION_70B,
      label: "mattshumer/reflection-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_CODELLAMA_34B_INSTRUCT,
      label: "meta-llama/codellama-34b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_CODELLAMA_70B_INSTRUCT,
      label: "meta-llama/codellama-70b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_2_13B_CHAT,
      label: "meta-llama/llama-2-13b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_2_70B_CHAT,
      label: "meta-llama/llama-2-70b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_405B,
      label: "meta-llama/llama-3.1-405b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_405B_INSTRUCT,
      label: "meta-llama/llama-3.1-405b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_70B_INSTRUCT,
      label: "meta-llama/llama-3.1-70b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_8B_INSTRUCT,
      label: "meta-llama/llama-3.1-8b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_11B_VISION_INSTRUCT,
      label: "meta-llama/llama-3.2-11b-vision-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_11B_VISION_INSTRUCT_FREE,
      label: "meta-llama/llama-3.2-11b-vision-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_1B_INSTRUCT,
      label: "meta-llama/llama-3.2-1b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_3B_INSTRUCT,
      label: "meta-llama/llama-3.2-3b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_90B_VISION_INSTRUCT,
      label: "meta-llama/llama-3.2-90b-vision-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_3_70B_INSTRUCT,
      label: "meta-llama/llama-3.3-70b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_3_70B_INSTRUCT_FREE,
      label: "meta-llama/llama-3.3-70b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_70B,
      label: "meta-llama/llama-3-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_70B_INSTRUCT,
      label: "meta-llama/llama-3-70b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_8B,
      label: "meta-llama/llama-3-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_8B_INSTRUCT,
      label: "meta-llama/llama-3-8b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_8B_INSTRUCT_FREE,
      label: "meta-llama/llama-3-8b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_GUARD_2_8B,
      label: "meta-llama/llama-guard-2-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_GUARD_3_8B,
      label: "meta-llama/llama-guard-3-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_5_MINI_128K_INSTRUCT,
      label: "microsoft/phi-3.5-mini-128k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MEDIUM_128K_INSTRUCT,
      label: "microsoft/phi-3-medium-128k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MEDIUM_128K_INSTRUCT_FREE,
      label: "microsoft/phi-3-medium-128k-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MEDIUM_4K_INSTRUCT,
      label: "microsoft/phi-3-medium-4k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MINI_128K_INSTRUCT,
      label: "microsoft/phi-3-mini-128k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MINI_128K_INSTRUCT_FREE,
      label: "microsoft/phi-3-mini-128k-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_4,
      label: "microsoft/phi-4",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_WIZARDLM_2_7B,
      label: "microsoft/wizardlm-2-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_WIZARDLM_2_8X22B,
      label: "microsoft/wizardlm-2-8x22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MIGTISSERA_SYNTHIA_70B,
      label: "migtissera/synthia-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_01,
      label: "minimax/minimax-01",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_CODESTRAL_2501,
      label: "mistralai/codestral-2501",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_CODESTRAL_MAMBA,
      label: "mistralai/codestral-mamba",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_3B,
      label: "mistralai/ministral-3b",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_8B,
      label: "mistralai/ministral-8b",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT,
      label: "mistralai/mistral-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_FREE,
      label: "mistralai/mistral-7b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_V0_1,
      label: "mistralai/mistral-7b-instruct-v0.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_V0_2,
      label: "mistralai/mistral-7b-instruct-v0.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_V0_3,
      label: "mistralai/mistral-7b-instruct-v0.3",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE,
      label: "mistralai/mistral-large",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE_2407,
      label: "mistralai/mistral-large-2407",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE_2411,
      label: "mistralai/mistral-large-2411",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_MEDIUM,
      label: "mistralai/mistral-medium",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_NEMO,
      label: "mistralai/mistral-nemo",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_NEMO_FREE,
      label: "mistralai/mistral-nemo:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SABA,
      label: "mistralai/mistral-saba",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL,
      label: "mistralai/mistral-small",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501,
      label: "mistralai/mistral-small-24b-instruct-2501",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501_FREE,
      label: "mistralai/mistral-small-24b-instruct-2501:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_TINY,
      label: "mistralai/mistral-tiny",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X22B,
      label: "mistralai/mixtral-8x22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X22B_INSTRUCT,
      label: "mistralai/mixtral-8x22b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X7B,
      label: "mistralai/mixtral-8x7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X7B_INSTRUCT,
      label: "mistralai/mixtral-8x7b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_PIXTRAL_12B,
      label: "mistralai/pixtral-12b",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_PIXTRAL_LARGE_2411,
      label: "mistralai/pixtral-large-2411",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_LLAMA_3_1_LUMIMAID_70B,
      label: "neversleep/llama-3.1-lumimaid-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_LLAMA_3_1_LUMIMAID_8B,
      label: "neversleep/llama-3.1-lumimaid-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_LLAMA_3_LUMIMAID_70B,
      label: "neversleep/llama-3-lumimaid-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_LLAMA_3_LUMIMAID_8B,
      label: "neversleep/llama-3-lumimaid-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_LLAMA_3_LUMIMAID_8B_EXTENDED,
      label: "neversleep/llama-3-lumimaid-8b:extended",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_NOROMAID_20B,
      label: "neversleep/noromaid-20b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_NOROMAID_MIXTRAL_8X7B_INSTRUCT,
      label: "neversleep/noromaid-mixtral-8x7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOTHINGIISREAL_MN_CELESTE_12B,
      label: "nothingiisreal/mn-celeste-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_2_PRO_LLAMA_3_8B,
      label: "nousresearch/hermes-2-pro-llama-3-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_2_THETA_LLAMA_3_8B,
      label: "nousresearch/hermes-2-theta-llama-3-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_3_LLAMA_3_1_405B,
      label: "nousresearch/hermes-3-llama-3.1-405b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_3_LLAMA_3_1_70B,
      label: "nousresearch/hermes-3-llama-3.1-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_CAPYBARA_34B,
      label: "nousresearch/nous-capybara-34b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_CAPYBARA_7B,
      label: "nousresearch/nous-capybara-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_2_MISTRAL_7B_DPO,
      label: "nousresearch/nous-hermes-2-mistral-7b-dpo",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_2_MIXTRAL_8X7B_DPO,
      label: "nousresearch/nous-hermes-2-mixtral-8x7b-dpo",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_2_MIXTRAL_8X7B_SFT,
      label: "nousresearch/nous-hermes-2-mixtral-8x7b-sft",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_2_VISION_7B,
      label: "nousresearch/nous-hermes-2-vision-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_LLAMA2_13B,
      label: "nousresearch/nous-hermes-llama2-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_LLAMA2_70B,
      label: "nousresearch/nous-hermes-llama2-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_NOUS_HERMES_YI_34B,
      label: "nousresearch/nous-hermes-yi-34b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_LLAMA_3_1_NEMOTRON_70B_INSTRUCT,
      label: "nvidia/llama-3.1-nemotron-70b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_LLAMA_3_1_NEMOTRON_70B_INSTRUCT_FREE,
      label: "nvidia/llama-3.1-nemotron-70b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_4_340B_INSTRUCT,
      label: "nvidia/nemotron-4-340b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_CHATGPT_4O_LATEST,
      label: "openai/chatgpt-4o-latest",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO,
      label: "openai/gpt-3.5-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_0125,
      label: "openai/gpt-3.5-turbo-0125",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_0301,
      label: "openai/gpt-3.5-turbo-0301",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_0613,
      label: "openai/gpt-3.5-turbo-0613",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_1106,
      label: "openai/gpt-3.5-turbo-1106",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_16K,
      label: "openai/gpt-3.5-turbo-16k",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_INSTRUCT,
      label: "openai/gpt-3.5-turbo-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4,
      label: "openai/gpt-4",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      label: "openai/gpt-4o",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_2024_05_13,
      label: "openai/gpt-4o-2024-05-13",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_2024_08_06,
      label: "openai/gpt-4o-2024-08-06",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_2024_11_20,
      label: "openai/gpt-4o-2024-11-20",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_EXTENDED,
      label: "openai/gpt-4o:extended",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      label: "openai/gpt-4o-mini",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI_2024_07_18,
      label: "openai/gpt-4o-mini-2024-07-18",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_0314,
      label: "openai/gpt-4-0314",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_1106_PREVIEW,
      label: "openai/gpt-4-1106-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_32K,
      label: "openai/gpt-4-32k",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_32K_0314,
      label: "openai/gpt-4-32k-0314",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_TURBO,
      label: "openai/gpt-4-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_TURBO_PREVIEW,
      label: "openai/gpt-4-turbo-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_VISION_PREVIEW,
      label: "openai/gpt-4-vision-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1,
      label: "openai/o1",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1_MINI,
      label: "openai/o1-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1_MINI_2024_09_12,
      label: "openai/o1-mini-2024-09-12",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1_PREVIEW,
      label: "openai/o1-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1_PREVIEW_2024_09_12,
      label: "openai/o1-preview-2024-09-12",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O3_MINI,
      label: "openai/o3-mini",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH,
      label: "openai/o3-mini-high",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_SHAP_E,
      label: "openai/shap-e",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENCHAT_OPENCHAT_7B,
      label: "openchat/openchat-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENCHAT_OPENCHAT_7B_FREE,
      label: "openchat/openchat-7b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENCHAT_OPENCHAT_8B,
      label: "openchat/openchat-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_AUTO,
      label: "openrouter/auto",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_CINEMATIKA_7B,
      label: "openrouter/cinematika-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPEN_ORCA_MISTRAL_7B_OPENORCA,
      label: "open-orca/mistral-7b-openorca",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_1_SONAR_HUGE_128K_ONLINE,
      label: "perplexity/llama-3.1-sonar-huge-128k-online",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_1_SONAR_LARGE_128K_CHAT,
      label: "perplexity/llama-3.1-sonar-large-128k-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_1_SONAR_LARGE_128K_ONLINE,
      label: "perplexity/llama-3.1-sonar-large-128k-online",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_1_SONAR_SMALL_128K_CHAT,
      label: "perplexity/llama-3.1-sonar-small-128k-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_1_SONAR_SMALL_128K_ONLINE,
      label: "perplexity/llama-3.1-sonar-small-128k-online",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_SONAR_LARGE_32K_CHAT,
      label: "perplexity/llama-3-sonar-large-32k-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_SONAR_LARGE_32K_ONLINE,
      label: "perplexity/llama-3-sonar-large-32k-online",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_SONAR_SMALL_32K_CHAT,
      label: "perplexity/llama-3-sonar-small-32k-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_LLAMA_3_SONAR_SMALL_32K_ONLINE,
      label: "perplexity/llama-3-sonar-small-32k-online",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR,
      label: "perplexity/sonar",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_REASONING,
      label: "perplexity/sonar-reasoning",
    },
    {
      value: PROVIDER_MODEL_TYPE.PHIND_PHIND_CODELLAMA_34B,
      label: "phind/phind-codellama-34b",
    },
    {
      value: PROVIDER_MODEL_TYPE.PYGMALIONAI_MYTHALION_13B,
      label: "pygmalionai/mythalion-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QVQ_72B_PREVIEW,
      label: "qwen/qvq-72b-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN2_5_VL_72B_INSTRUCT_FREE,
      label: "qwen/qwen2.5-vl-72b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_110B_CHAT,
      label: "qwen/qwen-110b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_14B_CHAT,
      label: "qwen/qwen-14b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_72B_INSTRUCT,
      label: "qwen/qwen-2.5-72b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_7B_INSTRUCT,
      label: "qwen/qwen-2.5-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_CODER_32B_INSTRUCT,
      label: "qwen/qwen-2.5-coder-32b-instruct",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_72B_INSTRUCT,
      label: "qwen/qwen-2-72b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_7B_INSTRUCT,
      label: "qwen/qwen-2-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_VL_72B_INSTRUCT,
      label: "qwen/qwen-2-vl-72b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_VL_7B_INSTRUCT,
      label: "qwen/qwen-2-vl-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_32B_CHAT,
      label: "qwen/qwen-32b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_4B_CHAT,
      label: "qwen/qwen-4b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_72B_CHAT,
      label: "qwen/qwen-72b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_7B_CHAT,
      label: "qwen/qwen-7b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_MAX,
      label: "qwen/qwen-max",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_PLUS,
      label: "qwen/qwen-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_TURBO,
      label: "qwen/qwen-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_VL_PLUS_FREE,
      label: "qwen/qwen-vl-plus:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWQ_32B_PREVIEW,
      label: "qwen/qwq-32b-preview",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.RAIFLE_SORCERERLM_8X22B,
      label: "raifle/sorcererlm-8x22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.RECURSAL_EAGLE_7B,
      label: "recursal/eagle-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.RECURSAL_RWKV_5_3B_AI_TOWN,
      label: "recursal/rwkv-5-3b-ai-town",
    },
    {
      value: PROVIDER_MODEL_TYPE.RWKV_RWKV_5_WORLD_3B,
      label: "rwkv/rwkv-5-world-3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_FIMBULVETR_11B_V2,
      label: "sao10k/fimbulvetr-11b-v2",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_1_70B_HANAMI_X1,
      label: "sao10k/l3.1-70b-hanami-x1",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_1_EURYALE_70B,
      label: "sao10k/l3.1-euryale-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_3_EURYALE_70B,
      label: "sao10k/l3.3-euryale-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_EURYALE_70B,
      label: "sao10k/l3-euryale-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_LUNARIS_8B,
      label: "sao10k/l3-lunaris-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_STHENO_8B,
      label: "sao10k/l3-stheno-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SNOWFLAKE_SNOWFLAKE_ARCTIC_INSTRUCT,
      label: "snowflake/snowflake-arctic-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.SOPHOSYMPATHEIA_MIDNIGHT_ROSE_70B,
      label: "sophosympatheia/midnight-rose-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SOPHOSYMPATHEIA_ROGUE_ROSE_103B_V0_2_FREE,
      label: "sophosympatheia/rogue-rose-103b-v0.2:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.TEKNIUM_OPENHERMES_2_5_MISTRAL_7B,
      label: "teknium/openhermes-2.5-mistral-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.TEKNIUM_OPENHERMES_2_MISTRAL_7B,
      label: "teknium/openhermes-2-mistral-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_ROCINANTE_12B,
      label: "thedrummer/rocinante-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_UNSLOPNEMO_12B,
      label: "thedrummer/unslopnemo-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.TOGETHERCOMPUTER_STRIPEDHYENA_HESSIAN_7B,
      label: "togethercomputer/stripedhyena-hessian-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.TOGETHERCOMPUTER_STRIPEDHYENA_NOUS_7B,
      label: "togethercomputer/stripedhyena-nous-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.UNDI95_REMM_SLERP_L2_13B,
      label: "undi95/remm-slerp-l2-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.UNDI95_TOPPY_M_7B,
      label: "undi95/toppy-m-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.UNDI95_TOPPY_M_7B_FREE,
      label: "undi95/toppy-m-7b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.XWIN_LM_XWIN_LM_70B,
      label: "xwin-lm/xwin-lm-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_2,
      label: "x-ai/grok-2",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_2_1212,
      label: "x-ai/grok-2-1212",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_2_MINI,
      label: "x-ai/grok-2-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_2_VISION_1212,
      label: "x-ai/grok-2-vision-1212",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_BETA,
      label: "x-ai/grok-beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_VISION_BETA,
      label: "x-ai/grok-vision-beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_1_5_34B_CHAT,
      label: "01-ai/yi-1.5-34b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_34B,
      label: "01-ai/yi-34b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_34B_200K,
      label: "01-ai/yi-34b-200k",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_34B_CHAT,
      label: "01-ai/yi-34b-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_6B,
      label: "01-ai/yi-6b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_LARGE,
      label: "01-ai/yi-large",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_LARGE_FC,
      label: "01-ai/yi-large-fc",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_LARGE_TURBO,
      label: "01-ai/yi-large-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.ZERO_ONE_AI_YI_VISION,
      label: "01-ai/yi-vision",
    },
  ],

  [PROVIDER_TYPE.GEMINI]: [
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
      label: "Gemini 2.0 Flash",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_1_5_FLASH,
      label: "Gemini 1.5 Flash",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_1_5_FLASH_8B,
      label: "Gemini 1.5 Flash-8B",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_1_5_PRO,
      label: "Gemini 1.5 Pro",
      structuredOutput: true,
    },
  ],

  [PROVIDER_TYPE.VERTEX_AI]: [
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17,
      label: "Gemini 2.5 Pro Preview 04.17",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_05_06,
      label: "Gemini 2.5 Pro Preview 05.06",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO_PREVIEW_03_25,
      label: "Gemini 2.5 Pro Preview 03.25",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO_EXP_03_25,
      label: "Gemini 2.5 Pro Exp 03.25",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH,
      label: "Gemini 2.0 Flash",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH_LITE,
      label: "Gemini 2.0 Flash Lite",
      structuredOutput: true,
    },
  ],

  [PROVIDER_TYPE.OLLAMA]: [
    // the list will be full filled base on data in localstorage
  ],
};

const useLLMProviderModelsData = () => {
  const { localModels, getLocalAIProviderData } = useLocalAIProviderData();

  const getProviderModels = useCallback(() => {
    return { ...PROVIDER_MODELS, ...localModels };
  }, [localModels]);

  const calculateModelProvider = useCallback(
    (modelName?: PROVIDER_MODEL_TYPE | ""): PROVIDER_TYPE | "" => {
      if (!modelName) {
        return "";
      }

      const provider = Object.entries(getProviderModels()).find(
        ([providerName, providerModels]) => {
          if (providerModels.find((pm) => modelName === pm.value)) {
            return providerName;
          }

          return false;
        },
      );

      if (!provider) {
        return "";
      }

      const [providerName] = provider;

      return providerName as PROVIDER_TYPE;
    },
    [getProviderModels],
  );

  const calculateDefaultModel = useCallback(
    (
      lastPickedModel: PROVIDER_MODEL_TYPE | "",
      setupProviders: PROVIDER_TYPE[],
      preferredProvider?: PROVIDER_TYPE | "",
      config: { structuredOutput?: boolean } = {},
    ) => {
      const { structuredOutput = false } = config;
      const lastPickedModelProvider = calculateModelProvider(lastPickedModel);

      const isLastPickedModelValid =
        !!lastPickedModelProvider &&
        setupProviders.includes(lastPickedModelProvider);

      if (isLastPickedModelValid) {
        return lastPickedModel;
      }

      const provider =
        preferredProvider ?? getDefaultProviderKey(setupProviders);

      if (provider) {
        if (
          PROVIDERS[provider].locationType === PROVIDER_LOCATION_TYPE.local &&
          !structuredOutput
        ) {
          return (
            (first(
              (getLocalAIProviderData(provider)?.models || "").split(","),
            )?.trim() as PROVIDER_MODEL_TYPE) ?? ""
          );
        } else if (
          PROVIDERS[provider].locationType === PROVIDER_LOCATION_TYPE.cloud
        ) {
          if (structuredOutput) {
            return (
              first(
                getProviderModels()[provider].filter((m) => m.structuredOutput),
              )?.value ?? ""
            );
          } else {
            return PROVIDERS[provider].defaultModel;
          }
        }
      }

      return "";
    },
    [calculateModelProvider, getLocalAIProviderData, getProviderModels],
  );

  return {
    getProviderModels,
    calculateModelProvider,
    calculateDefaultModel,
  };
};

export default useLLMProviderModelsData;
