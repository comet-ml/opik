import { useCallback } from "react";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_MODELS_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import useOpenAICompatibleModels from "@/hooks/useOpenAICompatibleModels";
import { parseComposedProviderType } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";
import first from "lodash/first";

export type ProviderResolver = (
  modelName?: PROVIDER_MODEL_TYPE | "",
) => COMPOSED_PROVIDER_TYPE | "";

export type ModelResolver = (
  lastPickedModel: PROVIDER_MODEL_TYPE | "",
  setupProviders: COMPOSED_PROVIDER_TYPE[],
  preferredProvider?: COMPOSED_PROVIDER_TYPE | "",
) => PROVIDER_MODEL_TYPE | "";

export const PROVIDER_MODELS: PROVIDER_MODELS_TYPE = {
  [PROVIDER_TYPE.OPIK_FREE]: [
    {
      value: PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
      label: "Free model", // This is overridden by model_label from config (e.g., "openai/gpt-4o-mini")
    },
  ],
  [PROVIDER_TYPE.OPEN_AI]: [
    // GPT-5.2 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_2,
      label: "GPT 5.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_2_CHAT_LATEST,
      label: "GPT 5.2 Chat Latest",
    },
    // GPT-5.1 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_1,
      label: "GPT 5.1",
    },
    // GPT-5 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_5,
      label: "GPT 5",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_MINI,
      label: "GPT 5 Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      label: "GPT 5 Nano",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_CHAT_LATEST,
      label: "GPT 5 Chat Latest",
    },

    // GPT O Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_O1,
      label: "GPT o1",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_O1_MINI,
      label: "GPT o1 Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_O3,
      label: "GPT o3",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_O3_MINI,
      label: "GPT o3 Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_O4_MINI,
      label: "GPT o4 Mini",
    },

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

    // GPT-4.1 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_1,
      label: "GPT 4.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_1_MINI,
      label: "GPT 4.1 Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_1_NANO,
      label: "GPT 4.1 Nano",
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
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
      label: "Claude Sonnet 4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      label: "Claude Opus 4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_5,
      label: "Claude Opus 4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1,
      label: "Claude Opus 4.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4,
      label: "Claude Opus 4",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5,
      label: "Claude Sonnet 4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4,
      label: "Claude Sonnet 4",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_3_7,
      label: "Claude Sonnet 3.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      label: "Claude Haiku 4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_3_5,
      label: "Claude Haiku 3.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_3,
      label: "Claude Haiku 3",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_20241022,
      label: "Claude 3.5 Sonnet 2024-10-22",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_OPUS_20240229,
      label: "Claude 3 Opus 2024-02-29",
    },
  ],

  [PROVIDER_TYPE.OPEN_ROUTER]: [
    {
      value: PROVIDER_MODEL_TYPE.AI21_JAMBA_LARGE_1_7,
      label: "ai21/jamba-large-1.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.AI21_JAMBA_MINI_1_7,
      label: "ai21/jamba-mini-1.7",
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
      value: PROVIDER_MODEL_TYPE.ALFREDPROS_CODELLAMA_7B_INSTRUCT_SOLIDITY,
      label: "alfredpros/codellama-7b-instruct-solidity",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALIBABA_TONGYI_DEEPRESEARCH_30B_A3B,
      label: "alibaba/tongyi-deepresearch-30b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALIBABA_TONGYI_DEEPRESEARCH_30B_A3B_FREE,
      label: "alibaba/tongyi-deepresearch-30b-a3b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALLENAI_OLMO_2_0325_32B_INSTRUCT,
      label: "allenai/olmo-2-0325-32b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.ALPINDALE_GOLIATH_120B,
      label: "alpindale/goliath-120b",
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
      value: PROVIDER_MODEL_TYPE.ANTHRACITE_ORG_MAGNUM_V4_72B,
      label: "anthracite-org/magnum-v4-72b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_HAIKU,
      label: "anthropic/claude-3-haiku",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_OPUS,
      label: "anthropic/claude-3-opus",
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
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_HAIKU_4_5,
      label: "anthropic/claude-haiku-4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_5_SONNET,
      label: "anthropic/claude-3.5-sonnet",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_7_SONNET,
      label: "anthropic/claude-3.7-sonnet",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_3_7_SONNET_THINKING,
      label: "anthropic/claude-3.7-sonnet:thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4,
      label: "anthropic/claude-opus-4",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_1,
      label: "anthropic/claude-opus-4.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_5,
      label: "anthropic/claude-opus-4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_6,
      label: "anthropic/claude-opus-4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4,
      label: "anthropic/claude-sonnet-4",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_5,
      label: "anthropic/claude-sonnet-4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_4_6,
      label: "anthropic/claude-sonnet-4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARCEE_AI_AFM_4_5B,
      label: "arcee-ai/afm-4.5b",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARCEE_AI_CODER_LARGE,
      label: "arcee-ai/coder-large",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARCEE_AI_MAESTRO_REASONING,
      label: "arcee-ai/maestro-reasoning",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARCEE_AI_SPOTLIGHT,
      label: "arcee-ai/spotlight",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARCEE_AI_VIRTUOSO_LARGE,
      label: "arcee-ai/virtuoso-large",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARLIAI_QWQ_32B_ARLIAI_RPR_V1,
      label: "arliai/qwq-32b-arliai-rpr-v1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARLIAI_QWQ_32B_ARLIAI_RPR_V1_FREE,
      label: "arliai/qwq-32b-arliai-rpr-v1:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.BAIDU_ERNIE_4_5_21B_A3B,
      label: "baidu/ernie-4.5-21b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.BAIDU_ERNIE_4_5_300B_A47B,
      label: "baidu/ernie-4.5-300b-a47b",
    },
    {
      value: PROVIDER_MODEL_TYPE.BAIDU_ERNIE_4_5_VL_28B_A3B,
      label: "baidu/ernie-4.5-vl-28b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.BAIDU_ERNIE_4_5_VL_424B_A47B,
      label: "baidu/ernie-4.5-vl-424b-a47b",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_UI_TARS_1_5_7B,
      label: "bytedance/ui-tars-1.5-7b",
    },
    {
      value:
        PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN_MISTRAL_24B_VENICE_EDITION_FREE,
      label: "cognitivecomputations/dolphin-mistral-24b-venice-edition:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_A,
      label: "cohere/command-a",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_08_2024,
      label: "cohere/command-r-08-2024",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R_PLUS_08_2024,
      label: "cohere/command-r-plus-08-2024",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_R7B_12_2024,
      label: "cohere/command-r7b-12-2024",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPCOGITO_COGITO_V2_PREVIEW_DEEPSEEK_671B,
      label: "deepcogito/cogito-v2-preview-deepseek-671b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPCOGITO_COGITO_V2_PREVIEW_LLAMA_109B_MOE,
      label: "deepcogito/cogito-v2-preview-llama-109b-moe",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT,
      label: "deepseek/deepseek-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT_V3_0324,
      label: "deepseek/deepseek-chat-v3-0324",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT_V3_0324_FREE,
      label: "deepseek/deepseek-chat-v3-0324:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT_V3_1,
      label: "deepseek/deepseek-chat-v3.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_PROVER_V2,
      label: "deepseek/deepseek-prover-v2",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1,
      label: "deepseek/deepseek-r1",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_0528,
      label: "deepseek/deepseek-r1-0528",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_0528_QWEN3_8B,
      label: "deepseek/deepseek-r1-0528-qwen3-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_0528_QWEN3_8B_FREE,
      label: "deepseek/deepseek-r1-0528-qwen3-8b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_0528_FREE,
      label: "deepseek/deepseek-r1-0528:free",
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
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_14B,
      label: "deepseek/deepseek-r1-distill-qwen-14b",
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
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V3_1_TERMINUS,
      label: "deepseek/deepseek-v3.1-terminus",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V3_2_EXP,
      label: "deepseek/deepseek-v3.2-exp",
    },
    {
      value: PROVIDER_MODEL_TYPE.ELEUTHERAI_LLEMMA_7B,
      label: "eleutherai/llemma_7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_001,
      label: "google/gemini-2.0-flash-001",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_EXP_FREE,
      label: "google/gemini-2.0-flash-exp:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_0_FLASH_LITE_001,
      label: "google/gemini-2.0-flash-lite-001",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH,
      label: "google/gemini-2.5-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH_IMAGE_PREVIEW,
      label: "google/gemini-2.5-flash-image-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH_LITE,
      label: "google/gemini-2.5-flash-lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH_LITE_PREVIEW_09_2025,
      label: "google/gemini-2.5-flash-lite-preview-09-2025",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH_PREVIEW_09_2025,
      label: "google/gemini-2.5-flash-preview-09-2025",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_PRO,
      label: "google/gemini-2.5-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_PRO_PREVIEW,
      label: "google/gemini-2.5-pro-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_PRO_PREVIEW_05_06,
      label: "google/gemini-2.5-pro-preview-05-06",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW,
      label: "google/gemini-3-flash-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_PRO_PREVIEW,
      label: "google/gemini-3-pro-preview",
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
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_12B_IT,
      label: "google/gemma-3-12b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_12B_IT_FREE,
      label: "google/gemma-3-12b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_27B_IT,
      label: "google/gemma-3-27b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_27B_IT_FREE,
      label: "google/gemma-3-27b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_4B_IT,
      label: "google/gemma-3-4b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_4B_IT_FREE,
      label: "google/gemma-3-4b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3N_E2B_IT_FREE,
      label: "google/gemma-3n-e2b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3N_E4B_IT,
      label: "google/gemma-3n-e4b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3N_E4B_IT_FREE,
      label: "google/gemma-3n-e4b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GRYPHE_MYTHOMAX_L2_13B,
      label: "gryphe/mythomax-l2-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCEPTION_MERCURY,
      label: "inception/mercury",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCEPTION_MERCURY_CODER,
      label: "inception/mercury-coder",
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
      value: PROVIDER_MODEL_TYPE.MANCER_WEAVER,
      label: "mancer/weaver",
    },
    {
      value: PROVIDER_MODEL_TYPE.MEITUAN_LONGCAT_FLASH_CHAT,
      label: "meituan/longcat-flash-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.MEITUAN_LONGCAT_FLASH_CHAT_FREE,
      label: "meituan/longcat-flash-chat:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_70B_INSTRUCT,
      label: "meta-llama/llama-3-70b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_8B_INSTRUCT,
      label: "meta-llama/llama-3-8b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_405B,
      label: "meta-llama/llama-3.1-405b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_405B_INSTRUCT,
      label: "meta-llama/llama-3.1-405b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_70B_INSTRUCT,
      label: "meta-llama/llama-3.1-70b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_1_8B_INSTRUCT,
      label: "meta-llama/llama-3.1-8b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_11B_VISION_INSTRUCT,
      label: "meta-llama/llama-3.2-11b-vision-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_1B_INSTRUCT,
      label: "meta-llama/llama-3.2-1b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_3B_INSTRUCT,
      label: "meta-llama/llama-3.2-3b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_3B_INSTRUCT_FREE,
      label: "meta-llama/llama-3.2-3b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_90B_VISION_INSTRUCT,
      label: "meta-llama/llama-3.2-90b-vision-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_3_70B_INSTRUCT,
      label: "meta-llama/llama-3.3-70b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_3_70B_INSTRUCT_FREE,
      label: "meta-llama/llama-3.3-70b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_4_MAVERICK,
      label: "meta-llama/llama-4-maverick",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_4_SCOUT,
      label: "meta-llama/llama-4-scout",
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
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_GUARD_4_12B,
      label: "meta-llama/llama-guard-4-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_MAI_DS_R1,
      label: "microsoft/mai-ds-r1",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_MAI_DS_R1_FREE,
      label: "microsoft/mai-ds-r1:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MEDIUM_128K_INSTRUCT,
      label: "microsoft/phi-3-medium-128k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_MINI_128K_INSTRUCT,
      label: "microsoft/phi-3-mini-128k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_3_5_MINI_128K_INSTRUCT,
      label: "microsoft/phi-3.5-mini-128k-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_4,
      label: "microsoft/phi-4",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_4_MULTIMODAL_INSTRUCT,
      label: "microsoft/phi-4-multimodal-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_4_REASONING_PLUS,
      label: "microsoft/phi-4-reasoning-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_WIZARDLM_2_8X22B,
      label: "microsoft/wizardlm-2-8x22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_01,
      label: "minimax/minimax-01",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M1,
      label: "minimax/minimax-m1",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_CODESTRAL_2501,
      label: "mistralai/codestral-2501",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_CODESTRAL_2508,
      label: "mistralai/codestral-2508",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_DEVSTRAL_MEDIUM,
      label: "mistralai/devstral-medium",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_DEVSTRAL_SMALL,
      label: "mistralai/devstral-small",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_DEVSTRAL_SMALL_2505,
      label: "mistralai/devstral-small-2505",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MAGISTRAL_MEDIUM_2506,
      label: "mistralai/magistral-medium-2506",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MAGISTRAL_MEDIUM_2506_THINKING,
      label: "mistralai/magistral-medium-2506:thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MAGISTRAL_SMALL_2506,
      label: "mistralai/magistral-small-2506",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_3B,
      label: "mistralai/ministral-3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_8B,
      label: "mistralai/ministral-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT,
      label: "mistralai/mistral-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_V0_1,
      label: "mistralai/mistral-7b-instruct-v0.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_V0_3,
      label: "mistralai/mistral-7b-instruct-v0.3",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_7B_INSTRUCT_FREE,
      label: "mistralai/mistral-7b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE,
      label: "mistralai/mistral-large",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE_2407,
      label: "mistralai/mistral-large-2407",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE_2411,
      label: "mistralai/mistral-large-2411",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_MEDIUM_3,
      label: "mistralai/mistral-medium-3",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_MEDIUM_3_1,
      label: "mistralai/mistral-medium-3.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_NEMO,
      label: "mistralai/mistral-nemo",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_NEMO_FREE,
      label: "mistralai/mistral-nemo:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SABA,
      label: "mistralai/mistral-saba",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL,
      label: "mistralai/mistral-small",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501,
      label: "mistralai/mistral-small-24b-instruct-2501",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501_FREE,
      label: "mistralai/mistral-small-24b-instruct-2501:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_3_1_24B_INSTRUCT,
      label: "mistralai/mistral-small-3.1-24b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_3_1_24B_INSTRUCT_FREE,
      label: "mistralai/mistral-small-3.1-24b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_3_2_24B_INSTRUCT,
      label: "mistralai/mistral-small-3.2-24b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_3_2_24B_INSTRUCT_FREE,
      label: "mistralai/mistral-small-3.2-24b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_TINY,
      label: "mistralai/mistral-tiny",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X22B_INSTRUCT,
      label: "mistralai/mixtral-8x22b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X7B_INSTRUCT,
      label: "mistralai/mixtral-8x7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_PIXTRAL_12B,
      label: "mistralai/pixtral-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_PIXTRAL_LARGE_2411,
      label: "mistralai/pixtral-large-2411",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_DEV_72B,
      label: "moonshotai/kimi-dev-72b",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2,
      label: "moonshotai/kimi-k2",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2_0905,
      label: "moonshotai/kimi-k2-0905",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2_FREE,
      label: "moonshotai/kimi-k2:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MORPH_MORPH_V3_FAST,
      label: "morph/morph-v3-fast",
    },
    {
      value: PROVIDER_MODEL_TYPE.MORPH_MORPH_V3_LARGE,
      label: "morph/morph-v3-large",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_LLAMA_3_1_LUMIMAID_8B,
      label: "neversleep/llama-3.1-lumimaid-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEVERSLEEP_NOROMAID_20B,
      label: "neversleep/noromaid-20b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_DEEPHERMES_3_MISTRAL_24B_PREVIEW,
      label: "nousresearch/deephermes-3-mistral-24b-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_2_PRO_LLAMA_3_8B,
      label: "nousresearch/hermes-2-pro-llama-3-8b",
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
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_4_405B,
      label: "nousresearch/hermes-4-405b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NOUSRESEARCH_HERMES_4_70B,
      label: "nousresearch/hermes-4-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_LLAMA_3_1_NEMOTRON_70B_INSTRUCT,
      label: "nvidia/llama-3.1-nemotron-70b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_LLAMA_3_1_NEMOTRON_ULTRA_253B_V1,
      label: "nvidia/llama-3.1-nemotron-ultra-253b-v1",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_NANO_9B_V2,
      label: "nvidia/nemotron-nano-9b-v2",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_NANO_9B_V2_FREE,
      label: "nvidia/nemotron-nano-9b-v2:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_CHATGPT_4O_LATEST,
      label: "openai/chatgpt-4o-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_CODEX_MINI,
      label: "openai/codex-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO,
      label: "openai/gpt-3.5-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_3_5_TURBO_0613,
      label: "openai/gpt-3.5-turbo-0613",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_0314,
      label: "openai/gpt-4-0314",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_1106_PREVIEW,
      label: "openai/gpt-4-1106-preview",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_1,
      label: "openai/gpt-4.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_1_MINI,
      label: "openai/gpt-4.1-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_1_NANO,
      label: "openai/gpt-4.1-nano",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O,
      label: "openai/gpt-4o",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_2024_05_13,
      label: "openai/gpt-4o-2024-05-13",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_2024_08_06,
      label: "openai/gpt-4o-2024-08-06",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_2024_11_20,
      label: "openai/gpt-4o-2024-11-20",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_AUDIO_PREVIEW,
      label: "openai/gpt-4o-audio-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      label: "openai/gpt-4o-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI_2024_07_18,
      label: "openai/gpt-4o-mini-2024-07-18",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI_SEARCH_PREVIEW,
      label: "openai/gpt-4o-mini-search-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_SEARCH_PREVIEW,
      label: "openai/gpt-4o-search-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_EXTENDED,
      label: "openai/gpt-4o:extended",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5,
      label: "openai/gpt-5",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_CHAT,
      label: "openai/gpt-5-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_CODEX,
      label: "openai/gpt-5-codex",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_MINI,
      label: "openai/gpt-5-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
      label: "openai/gpt-5-nano",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_PRO,
      label: "openai/gpt-5-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_1,
      label: "openai/gpt-5.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_1_CHAT,
      label: "openai/gpt-5.1-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_1_CODEX,
      label: "openai/gpt-5.1-codex",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_1_CODEX_MINI,
      label: "openai/gpt-5.1-codex-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_2,
      label: "openai/gpt-5.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_2_CHAT_LATEST,
      label: "openai/gpt-5.2-chat-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_OSS_120B,
      label: "openai/gpt-oss-120b",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_OSS_20B,
      label: "openai/gpt-oss-20b",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_OSS_20B_FREE,
      label: "openai/gpt-oss-20b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1,
      label: "openai/o1",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O1_PRO,
      label: "openai/o1-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O3,
      label: "openai/o3",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O3_MINI,
      label: "openai/o3-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O3_MINI_HIGH,
      label: "openai/o3-mini-high",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O3_PRO,
      label: "openai/o3-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O4_MINI,
      label: "openai/o4-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_O4_MINI_HIGH,
      label: "openai/o4-mini-high",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENGVLAB_INTERNVL3_78B,
      label: "opengvlab/internvl3-78b",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_AUTO,
      label: "openrouter/auto",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR,
      label: "perplexity/sonar",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_DEEP_RESEARCH,
      label: "perplexity/sonar-deep-research",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_PRO,
      label: "perplexity/sonar-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_REASONING,
      label: "perplexity/sonar-reasoning",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_REASONING_PRO,
      label: "perplexity/sonar-reasoning-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_72B_INSTRUCT,
      label: "qwen/qwen-2.5-72b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_72B_INSTRUCT_FREE,
      label: "qwen/qwen-2.5-72b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_7B_INSTRUCT,
      label: "qwen/qwen-2.5-7b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_CODER_32B_INSTRUCT,
      label: "qwen/qwen-2.5-coder-32b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_CODER_32B_INSTRUCT_FREE,
      label: "qwen/qwen-2.5-coder-32b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_VL_7B_INSTRUCT,
      label: "qwen/qwen-2.5-vl-7b-instruct",
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
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_PLUS_2025_07_28,
      label: "qwen/qwen-plus-2025-07-28",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_PLUS_2025_07_28_THINKING,
      label: "qwen/qwen-plus-2025-07-28:thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_TURBO,
      label: "qwen/qwen-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_VL_MAX,
      label: "qwen/qwen-vl-max",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_VL_PLUS,
      label: "qwen/qwen-vl-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN2_5_VL_32B_INSTRUCT,
      label: "qwen/qwen2.5-vl-32b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN2_5_VL_32B_INSTRUCT_FREE,
      label: "qwen/qwen2.5-vl-32b-instruct:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN2_5_VL_72B_INSTRUCT,
      label: "qwen/qwen2.5-vl-72b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_14B,
      label: "qwen/qwen3-14b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_14B_FREE,
      label: "qwen/qwen3-14b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_235B_A22B,
      label: "qwen/qwen3-235b-a22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_235B_A22B_2507,
      label: "qwen/qwen3-235b-a22b-2507",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_235B_A22B_THINKING_2507,
      label: "qwen/qwen3-235b-a22b-thinking-2507",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_235B_A22B_FREE,
      label: "qwen/qwen3-235b-a22b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_30B_A3B,
      label: "qwen/qwen3-30b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_30B_A3B_INSTRUCT_2507,
      label: "qwen/qwen3-30b-a3b-instruct-2507",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_30B_A3B_THINKING_2507,
      label: "qwen/qwen3-30b-a3b-thinking-2507",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_30B_A3B_FREE,
      label: "qwen/qwen3-30b-a3b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_32B,
      label: "qwen/qwen3-32b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_4B_FREE,
      label: "qwen/qwen3-4b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8B,
      label: "qwen/qwen3-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER,
      label: "qwen/qwen3-coder",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER_30B_A3B_INSTRUCT,
      label: "qwen/qwen3-coder-30b-a3b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER_FLASH,
      label: "qwen/qwen3-coder-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER_PLUS,
      label: "qwen/qwen3-coder-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER_FREE,
      label: "qwen/qwen3-coder:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_MAX,
      label: "qwen/qwen3-max",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_NEXT_80B_A3B_INSTRUCT,
      label: "qwen/qwen3-next-80b-a3b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_NEXT_80B_A3B_THINKING,
      label: "qwen/qwen3-next-80b-a3b-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_235B_A22B_INSTRUCT,
      label: "qwen/qwen3-vl-235b-a22b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_235B_A22B_THINKING,
      label: "qwen/qwen3-vl-235b-a22b-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWQ_32B,
      label: "qwen/qwq-32b",
    },
    {
      value: PROVIDER_MODEL_TYPE.RAIFLE_SORCERERLM_8X22B,
      label: "raifle/sorcererlm-8x22b",
    },
    {
      value: PROVIDER_MODEL_TYPE.RELACE_RELACE_APPLY_3,
      label: "relace/relace-apply-3",
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
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_1_EURYALE_70B,
      label: "sao10k/l3.1-euryale-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAO10K_L3_3_EURYALE_70B,
      label: "sao10k/l3.3-euryale-70b",
    },
    {
      value: PROVIDER_MODEL_TYPE.STEPFUN_AI_STEP3,
      label: "stepfun-ai/step3",
    },
    {
      value: PROVIDER_MODEL_TYPE.SWITCHPOINT_ROUTER,
      label: "switchpoint/router",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HUNYUAN_A13B_INSTRUCT,
      label: "tencent/hunyuan-a13b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_ANUBIS_70B_V1_1,
      label: "thedrummer/anubis-70b-v1.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_CYDONIA_24B_V4_1,
      label: "thedrummer/cydonia-24b-v4.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_ROCINANTE_12B,
      label: "thedrummer/rocinante-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_SKYFALL_36B_V2,
      label: "thedrummer/skyfall-36b-v2",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_UNSLOPNEMO_12B,
      label: "thedrummer/unslopnemo-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.THUDM_GLM_4_1V_9B_THINKING,
      label: "thudm/glm-4.1v-9b-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.TNGTECH_DEEPSEEK_R1T_CHIMERA,
      label: "tngtech/deepseek-r1t-chimera",
    },
    {
      value: PROVIDER_MODEL_TYPE.TNGTECH_DEEPSEEK_R1T_CHIMERA_FREE,
      label: "tngtech/deepseek-r1t-chimera:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.TNGTECH_DEEPSEEK_R1T2_CHIMERA,
      label: "tngtech/deepseek-r1t2-chimera",
    },
    {
      value: PROVIDER_MODEL_TYPE.TNGTECH_DEEPSEEK_R1T2_CHIMERA_FREE,
      label: "tngtech/deepseek-r1t2-chimera:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.UNDI95_REMM_SLERP_L2_13B,
      label: "undi95/remm-slerp-l2-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_3,
      label: "x-ai/grok-3",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_3_BETA,
      label: "x-ai/grok-3-beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_3_MINI,
      label: "x-ai/grok-3-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_3_MINI_BETA,
      label: "x-ai/grok-3-mini-beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4,
      label: "x-ai/grok-4",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_FAST,
      label: "x-ai/grok-4-fast",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_CODE_FAST_1,
      label: "x-ai/grok-code-fast-1",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_32B,
      label: "z-ai/glm-4-32b",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5,
      label: "z-ai/glm-4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5_AIR,
      label: "z-ai/glm-4.5-air",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5_AIR_FREE,
      label: "z-ai/glm-4.5-air:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5V,
      label: "z-ai/glm-4.5v",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_6,
      label: "z-ai/glm-4.6",
    },
  ],

  [PROVIDER_TYPE.GEMINI]: [
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      label: "Gemini 3 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
      label: "Gemini 3 Pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
      label: "Gemini 2.5 Pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      label: "Gemini 2.5 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
      label: "Gemini 2.5 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH,
      label: "Gemini 2.0 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_0_FLASH_LITE,
      label: "Gemini 2.0 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_1_5_FLASH,
      label: "Gemini 1.5 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_1_5_FLASH_8B,
      label: "Gemini 1.5 Flash-8B",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_1_5_PRO,
      label: "Gemini 1.5 Pro",
    },
  ],

  [PROVIDER_TYPE.VERTEX_AI]: [
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO,
      label: "Gemini 3 Pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17,
      label: "Gemini 2.5 Pro Preview 04.17",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_05_06,
      label: "Gemini 2.5 Pro Preview 05.06",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO_PREVIEW_03_25,
      label: "Gemini 2.5 Pro Preview 03.25",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO_EXP_03_25,
      label: "Gemini 2.5 Pro Exp 03.25",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH,
      label: "Gemini 2.0 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH_LITE,
      label: "Gemini 2.0 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
      label: "Gemini 2.5 Pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      label: "Gemini 2.5 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
      label: "Gemini 2.5 Flash Lite Preview 06-17",
    },
  ],

  [PROVIDER_TYPE.CUSTOM]: [
    // the list will be fully populated based on the provider config response
  ],

  [PROVIDER_TYPE.OLLAMA]: [
    // the list will be fully populated based on the provider config response
  ],

  [PROVIDER_TYPE.BEDROCK]: [
    // the list will be fully populated based on the provider config response
  ],
};

const useLLMProviderModelsData = () => {
  const openAICompatibleModels = useOpenAICompatibleModels();

  const getProviderModels = useCallback(() => {
    return {
      ...PROVIDER_MODELS,
      ...openAICompatibleModels,
    } as ProviderModelsMap;
  }, [openAICompatibleModels]);

  const calculateModelProvider: ProviderResolver = useCallback(
    (modelName) => {
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

      return provider[0] as COMPOSED_PROVIDER_TYPE;
    },
    [getProviderModels],
  );

  const calculateDefaultModel: ModelResolver = useCallback(
    (lastPickedModel, setupProviders, preferredProvider?) => {
      const lastPickedModelProvider = calculateModelProvider(lastPickedModel);

      const isLastPickedModelValid =
        !!lastPickedModelProvider &&
        setupProviders.includes(lastPickedModelProvider);

      if (isLastPickedModelValid) {
        return lastPickedModel;
      }

      const composedProviderType =
        preferredProvider ?? (first(setupProviders) || "");
      const providerType = parseComposedProviderType(composedProviderType);

      if (composedProviderType && providerType) {
        if (PROVIDERS[providerType]?.defaultModel) {
          return PROVIDERS[providerType].defaultModel;
        } else {
          return first(getProviderModels()[composedProviderType])?.value ?? "";
        }
      }

      return "";
    },
    [calculateModelProvider, getProviderModels],
  );

  return {
    getProviderModels,
    calculateModelProvider,
    calculateDefaultModel,
  };
};

export default useLLMProviderModelsData;
