import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_MODELS_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

/**
 * Retained static model catalogue.
 *
 * PROVIDER_MODELS is no longer read at runtime — the hook below fetches
 * /v1/private/llm/models and merges that with useOpenAICompatibleModels.
 * The constant is kept in the tree as an easy rollback handle: if the CDN
 * flow needs to be reverted in production, wiring getProviderModels back
 * to this map is a one-line change. OPIK-5022 will delete both this
 * constant and the Java enums once the registry has soaked in prod.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export const PROVIDER_MODELS: PROVIDER_MODELS_TYPE = {
  [PROVIDER_TYPE.OPIK_FREE]: [
    {
      value: PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
      label: "Free model", // This is overridden by model_label from config (e.g., "openai/gpt-4o-mini")
    },
  ],
  [PROVIDER_TYPE.OPEN_AI]: [
    {
      value: PROVIDER_MODEL_TYPE.GPT_6_1_SOL,
      label: "GPT 6.1 Sol",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_6_ASTRA,
      label: "GPT 6 Astra",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_6_LUNA,
      label: "GPT 6 Luna",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_6_SOL,
      label: "GPT 6 Sol",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_6_LUNA,
      label: "GPT 5.6 Luna",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_6_SOL,
      label: "GPT 5.6 Sol",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_6_TERRA,
      label: "GPT 5.6 Terra",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_5,
      label: "GPT 5.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_4,
      label: "GPT 5.4",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_4_MINI,
      label: "GPT 5.4 Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_4_NANO,
      label: "GPT 5.4 Nano",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_2,
      label: "GPT 5.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_5_1,
      label: "GPT 5.1",
    },
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
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O,
      label: "GPT 4o",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      label: "GPT 4o Mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4,
      label: "GPT 4",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO,
      label: "GPT 4 Turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_O4_MINI,
      label: "GPT o4 Mini",
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
      value: PROVIDER_MODEL_TYPE.GPT_O1,
      label: "GPT o1",
    },
  ],

  [PROVIDER_TYPE.ANTHROPIC]: [
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5,
      label: "Claude Opus 5.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5,
      label: "Claude Opus 5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5,
      label: "Claude Sonnet 5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_8,
      label: "Claude Opus 4.8",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      label: "Claude Opus 4.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
      label: "Claude Opus 4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
      label: "Claude Sonnet 4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_5,
      label: "Claude Opus 4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5_20250929,
      label: "Claude Sonnet 4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      label: "Claude Haiku 4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_FABLE_5,
      label: "Claude Fable 5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_FABLE_5_1,
      label: "Claude Fable 5.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_5,
      label: "Claude Mythos 5",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_5_1,
      label: "Claude Mythos 5.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_MYTHOS_PREVIEW,
      label: "Claude Mythos Preview",
    },
  ],

  [PROVIDER_TYPE.OPEN_ROUTER]: [
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_2_0,
      label: "aion-labs/aion-2.0",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_3_0,
      label: "aion-labs/aion-3.0",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_3_0_MINI,
      label: "aion-labs/aion-3.0-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_3_5,
      label: "aion-labs/aion-3.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_3_5_MINI,
      label: "aion-labs/aion-3.5-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.AION_LABS_AION_RP_LLAMA_3_1_8B,
      label: "aion-labs/aion-rp-llama-3.1-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.AMAZON_NOVA_2_LITE_V1,
      label: "amazon/nova-2-lite-v1",
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
      value: PROVIDER_MODEL_TYPE.AMAZON_NOVA_PREMIER_V1,
      label: "amazon/nova-premier-v1",
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
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_FABLE_5,
      label: "anthropic/claude-fable-5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_FABLE_5_1,
      label: "anthropic/claude-fable-5.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_HAIKU_4_5,
      label: "anthropic/claude-haiku-4.5",
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
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_7,
      label: "anthropic/claude-opus-4.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_4_8,
      label: "anthropic/claude-opus-4.8",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_5,
      label: "anthropic/claude-opus-5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_5_5,
      label: "anthropic/claude-opus-5.5",
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
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_5,
      label: "anthropic/claude-sonnet-5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_5_5,
      label: "anthropic/claude-sonnet-5.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.ARCEE_AI_TRINITY_LARGE_THINKING,
      label: "arcee-ai/trinity-large-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_SEED_SEED_1_6,
      label: "bytedance-seed/seed-1.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_SEED_SEED_1_6_FLASH,
      label: "bytedance-seed/seed-1.6-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_SEED_SEED_2_1_TURBO,
      label: "bytedance-seed/seed-2-1-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_SEED_SEED_2_0_CODE,
      label: "bytedance-seed/seed-2.0-code",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_SEED_SEED_2_0_LITE,
      label: "bytedance-seed/seed-2.0-lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_SEED_SEED_2_0_MINI,
      label: "bytedance-seed/seed-2.0-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.BYTEDANCE_UI_TARS_1_5_7B,
      label: "bytedance/ui-tars-1.5-7b",
    },
    {
      value:
        PROVIDER_MODEL_TYPE.COGNITIVECOMPUTATIONS_DOLPHIN_MISTRAL_24B_VENICE_EDITION,
      label: "cognitivecomputations/dolphin-mistral-24b-venice-edition",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_A,
      label: "cohere/command-a",
    },
    {
      value: PROVIDER_MODEL_TYPE.COHERE_COMMAND_A_PLUS,
      label: "cohere/command-a-plus",
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
      value: PROVIDER_MODEL_TYPE.COHERE_NORTH_MINI_CODE_FREE,
      label: "cohere/north-mini-code:free",
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
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_CHAT_V3_1,
      label: "deepseek/deepseek-chat-v3.1",
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
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_FLASH,
      label: "deepseek/deepseek-v4-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_FLASH_0731,
      label: "deepseek/deepseek-v4-flash-0731",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_FLASH_VISION_EXP,
      label: "deepseek/deepseek-v4-flash-vision-exp",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_PRO,
      label: "deepseek/deepseek-v4-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_PRO_0813,
      label: "deepseek/deepseek-v4-pro-0813",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_1_FLASH,
      label: "deepseek/deepseek-v4.1-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.FIREWORKS_EMBER_1,
      label: "fireworks/ember-1",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH,
      label: "google/gemini-2.5-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH_IMAGE,
      label: "google/gemini-2.5-flash-image",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_2_5_FLASH_LITE,
      label: "google/gemini-2.5-flash-lite",
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
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_FLASH_PREVIEW,
      label: "google/gemini-3-flash-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_PRO_IMAGE,
      label: "google/gemini-3-pro-image",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_PRO_IMAGE_PREVIEW,
      label: "google/gemini-3-pro-image-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_FLASH_IMAGE,
      label: "google/gemini-3.1-flash-image",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_FLASH_IMAGE_PREVIEW,
      label: "google/gemini-3.1-flash-image-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_FLASH_LITE,
      label: "google/gemini-3.1-flash-lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_FLASH_LITE_IMAGE,
      label: "google/gemini-3.1-flash-lite-image",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_FLASH_LITE_PREVIEW,
      label: "google/gemini-3.1-flash-lite-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_PRO_PREVIEW,
      label: "google/gemini-3.1-pro-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS,
      label: "google/gemini-3.1-pro-preview-customtools",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_5_FLASH,
      label: "google/gemini-3.5-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_5_FLASH_LITE,
      label: "google/gemini-3.5-flash-lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_6_FLASH,
      label: "google/gemini-3.6-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_7_FLASH,
      label: "google/gemini-3.7-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_3_8_FLASH,
      label: "google/gemini-3.8-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_27B_IT,
      label: "google/gemma-2-27b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_12B_IT,
      label: "google/gemma-3-12b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_27B_IT,
      label: "google/gemma-3-27b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_3_4B_IT,
      label: "google/gemma-3-4b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_4_26B_A4B_IT,
      label: "google/gemma-4-26b-a4b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_4_26B_A4B_IT_FREE,
      label: "google/gemma-4-26b-a4b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_4_31B_IT,
      label: "google/gemma-4-31b-it",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_4_31B_IT_FREE,
      label: "google/gemma-4-31b-it:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_LYRIA_3_CLIP_PREVIEW,
      label: "google/lyria-3-clip-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_LYRIA_3_PRO_PREVIEW,
      label: "google/lyria-3-pro-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GRYPHE_MYTHOMAX_L2_13B,
      label: "gryphe/mythomax-l2-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.IBM_GRANITE_GRANITE_4_0_H_MICRO,
      label: "ibm-granite/granite-4.0-h-micro",
    },
    {
      value: PROVIDER_MODEL_TYPE.IBM_GRANITE_GRANITE_4_2_8B,
      label: "ibm-granite/granite-4.2-8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCEPTION_MERCURY_2,
      label: "inception/mercury-2",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCEPTION_MERCURY_2_5,
      label: "inception/mercury-2.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCLUSIONAI_LING_3_0_FLASH,
      label: "inclusionai/ling-3.0-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCLUSIONAI_LING_3_0_FLASH_FIN,
      label: "inclusionai/ling-3.0-flash-fin",
    },
    {
      value: PROVIDER_MODEL_TYPE.INCLUSIONAI_LING_3_0_FLASH_VL,
      label: "inclusionai/ling-3.0-flash-vl",
    },
    {
      value: PROVIDER_MODEL_TYPE.INFERENCE_NET_SCHEMATRON_V2_SMALL,
      label: "inference-net/schematron-v2-small",
    },
    {
      value: PROVIDER_MODEL_TYPE.INFERENCE_NET_SCHEMATRON_V2_TURBO,
      label: "inference-net/schematron-v2-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.LIQUID_LFM_2_5_2_6B_FREE,
      label: "liquid/lfm-2.5-2.6b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.MANCER_WEAVER,
      label: "mancer/weaver",
    },
    {
      value: PROVIDER_MODEL_TYPE.MEITUAN_LONGCAT_2_0,
      label: "meituan/longcat-2.0",
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
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_1B_INSTRUCT,
      label: "meta-llama/llama-3.2-1b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_2_3B_INSTRUCT,
      label: "meta-llama/llama-3.2-3b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_3_3_70B_INSTRUCT,
      label: "meta-llama/llama-3.3-70b-instruct",
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
      value: PROVIDER_MODEL_TYPE.META_LLAMA_LLAMA_GUARD_4_12B,
      label: "meta-llama/llama-guard-4-12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_MUSE_GLIMMER_30B,
      label: "meta/muse-glimmer-30b",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_MUSE_SPARK_1_1,
      label: "meta/muse-spark-1.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_MUSE_SPARK_1_2,
      label: "meta/muse-spark-1.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_MUSE_SPARK_1_2_CONTRIBUTOR,
      label: "meta/muse-spark-1.2-contributor",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_MUSE_SPARK_1_3,
      label: "meta/muse-spark-1.3",
    },
    {
      value: PROVIDER_MODEL_TYPE.META_MUSE_SPARK_1_3_CONTRIBUTOR,
      label: "meta/muse-spark-1.3-contributor",
    },
    {
      value: PROVIDER_MODEL_TYPE.MICROSOFT_PHI_4,
      label: "microsoft/phi-4",
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
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M2,
      label: "minimax/minimax-m2",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M2_HER,
      label: "minimax/minimax-m2-her",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M2_1,
      label: "minimax/minimax-m2.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M2_5,
      label: "minimax/minimax-m2.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M2_7,
      label: "minimax/minimax-m2.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.MINIMAX_MINIMAX_M3,
      label: "minimax/minimax-m3",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_CODESTRAL_2508,
      label: "mistralai/codestral-2508",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_DEVSTRAL_2512,
      label: "mistralai/devstral-2512",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_14B_2512,
      label: "mistralai/ministral-14b-2512",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_3B_2512,
      label: "mistralai/ministral-3b-2512",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MINISTRAL_8B_2512,
      label: "mistralai/ministral-8b-2512",
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
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_LARGE_2512,
      label: "mistralai/mistral-large-2512",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_MEDIUM_3,
      label: "mistralai/mistral-medium-3",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_MEDIUM_3_5,
      label: "mistralai/mistral-medium-3-5",
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
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SABA,
      label: "mistralai/mistral-saba",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501,
      label: "mistralai/mistral-small-24b-instruct-2501",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_2603,
      label: "mistralai/mistral-small-2603",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_3_1_24B_INSTRUCT,
      label: "mistralai/mistral-small-3.1-24b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MISTRAL_SMALL_3_2_24B_INSTRUCT,
      label: "mistralai/mistral-small-3.2-24b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_MIXTRAL_8X22B_INSTRUCT,
      label: "mistralai/mixtral-8x22b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.MISTRALAI_VOXTRAL_SMALL_24B_2507,
      label: "mistralai/voxtral-small-24b-2507",
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
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2_THINKING,
      label: "moonshotai/kimi-k2-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2_5,
      label: "moonshotai/kimi-k2.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2_6,
      label: "moonshotai/kimi-k2.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K2_7_CODE,
      label: "moonshotai/kimi-k2.7-code",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_K3,
      label: "moonshotai/kimi-k3",
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
      value: PROVIDER_MODEL_TYPE.NEX_AGI_NEX_N2_5_MINI,
      label: "nex-agi/nex-n2.5-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.NEX_AGI_NEX_N2_5_PRO,
      label: "nex-agi/nex-n2.5-pro",
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
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_NANO_30B_A3B,
      label: "nvidia/nemotron-3-nano-30b-a3b",
    },
    {
      value:
        PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_NANO_OMNI_30B_A3B_REASONING_FREE,
      label: "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_SUPER_120B_A12B,
      label: "nvidia/nemotron-3-super-120b-a12b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_SUPER_120B_A12B_FREE,
      label: "nvidia/nemotron-3-super-120b-a12b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_ULTRA_550B_A55B,
      label: "nvidia/nemotron-3-ultra-550b-a55b",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_ULTRA_550B_A55B_FREE,
      label: "nvidia/nemotron-3-ultra-550b-a55b:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_5_CONTENT_SAFETY,
      label: "nvidia/nemotron-3.5-content-safety",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_5_CONTENT_SAFETY_FREE,
      label: "nvidia/nemotron-3.5-content-safety:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_5_LIGHTNING,
      label: "nvidia/nemotron-3.5-lightning",
    },
    {
      value: PROVIDER_MODEL_TYPE.NVIDIA_NEMOTRON_3_5_LIGHTNING_FREE,
      label: "nvidia/nemotron-3.5-lightning:free",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4_TURBO,
      label: "openai/gpt-4-turbo",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
      label: "openai/gpt-4o-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI_2024_07_18,
      label: "openai/gpt-4o-mini-2024-07-18",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5,
      label: "openai/gpt-5",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_IMAGE,
      label: "openai/gpt-5-image",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_IMAGE_MINI,
      label: "openai/gpt-5-image-mini",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_1_CODEX,
      label: "openai/gpt-5.1-codex",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_1_CODEX_MAX,
      label: "openai/gpt-5.1-codex-max",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_2_CHAT,
      label: "openai/gpt-5.2-chat",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_2_CODEX,
      label: "openai/gpt-5.2-codex",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_2_PRO,
      label: "openai/gpt-5.2-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_3_CODEX,
      label: "openai/gpt-5.3-codex",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_4,
      label: "openai/gpt-5.4",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_4_IMAGE_2,
      label: "openai/gpt-5.4-image-2",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_4_MINI,
      label: "openai/gpt-5.4-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_4_NANO,
      label: "openai/gpt-5.4-nano",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_4_PRO,
      label: "openai/gpt-5.4-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_5,
      label: "openai/gpt-5.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_5_PRO,
      label: "openai/gpt-5.5-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_6_LUNA,
      label: "openai/gpt-5.6-luna",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_6_LUNA_PRO,
      label: "openai/gpt-5.6-luna-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_6_SOL,
      label: "openai/gpt-5.6-sol",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_6_SOL_PRO,
      label: "openai/gpt-5.6-sol-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_6_TERRA,
      label: "openai/gpt-5.6-terra",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_6_TERRA_PRO,
      label: "openai/gpt-5.6-terra-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_ASTRA,
      label: "openai/gpt-6-astra",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_ASTRA_PRO,
      label: "openai/gpt-6-astra-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_LUNA,
      label: "openai/gpt-6-luna",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_LUNA_PRO,
      label: "openai/gpt-6-luna-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_SOL,
      label: "openai/gpt-6-sol",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_SOL_PRO,
      label: "openai/gpt-6-sol-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_1_SOL,
      label: "openai/gpt-6.1-sol",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_6_1_SOL_PRO,
      label: "openai/gpt-6.1-sol-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_AUDIO,
      label: "openai/gpt-audio",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_AUDIO_MINI,
      label: "openai/gpt-audio-mini",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_CHAT_LATEST,
      label: "openai/gpt-chat-latest",
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
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_OSS_SAFEGUARD_20B,
      label: "openai/gpt-oss-safeguard-20b",
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
      value: PROVIDER_MODEL_TYPE.OPENROUTER_AUTO,
      label: "openrouter/auto",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_AUTO_BETA,
      label: "openrouter/auto-beta",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_BODYBUILDER,
      label: "openrouter/bodybuilder",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_FREE,
      label: "openrouter/free",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_FUSION,
      label: "openrouter/fusion",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENROUTER_PARETO_CODE,
      label: "openrouter/pareto-code",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERCEPTRON_PERCEPTRON_MK1,
      label: "perceptron/perceptron-mk1",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERCEPTRON_PERCEPTRON_MK1_5,
      label: "perceptron/perceptron-mk1.5",
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
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_PRO_SEARCH,
      label: "perplexity/sonar-pro-search",
    },
    {
      value: PROVIDER_MODEL_TYPE.PERPLEXITY_SONAR_REASONING_PRO,
      label: "perplexity/sonar-reasoning-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.POOLSIDE_LAGUNA_S_2_1,
      label: "poolside/laguna-s-2.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.POOLSIDE_LAGUNA_S_2_1_FREE,
      label: "poolside/laguna-s-2.1:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.POOLSIDE_LAGUNA_XS_2_1,
      label: "poolside/laguna-xs-2.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.POOLSIDE_LAGUNA_XS_2_1_FREE,
      label: "poolside/laguna-xs-2.1:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.PRISM_ML_TERNARY_BONSAI_2_27B,
      label: "prism-ml/ternary-bonsai-2-27b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_2_5_72B_INSTRUCT,
      label: "qwen/qwen-2.5-72b-instruct",
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
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_PLUS,
      label: "qwen/qwen-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN_PLUS_2025_07_28,
      label: "qwen/qwen-plus-2025-07-28",
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
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_32B,
      label: "qwen/qwen3-32b",
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
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER_NEXT,
      label: "qwen/qwen3-coder-next",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_CODER_PLUS,
      label: "qwen/qwen3-coder-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_MAX,
      label: "qwen/qwen3-max",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_MAX_THINKING,
      label: "qwen/qwen3-max-thinking",
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
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_30B_A3B_INSTRUCT,
      label: "qwen/qwen3-vl-30b-a3b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_30B_A3B_THINKING,
      label: "qwen/qwen3-vl-30b-a3b-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_32B_INSTRUCT,
      label: "qwen/qwen3-vl-32b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_8B_INSTRUCT,
      label: "qwen/qwen3-vl-8b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_VL_8B_THINKING,
      label: "qwen/qwen3-vl-8b-thinking",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_122B_A10B,
      label: "qwen/qwen3.5-122b-a10b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_27B,
      label: "qwen/qwen3.5-27b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_35B_A3B,
      label: "qwen/qwen3.5-35b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_397B_A17B,
      label: "qwen/qwen3.5-397b-a17b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_9B,
      label: "qwen/qwen3.5-9b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_FLASH_02_23,
      label: "qwen/qwen3.5-flash-02-23",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_PLUS_02_15,
      label: "qwen/qwen3.5-plus-02-15",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_5_PLUS_20260420,
      label: "qwen/qwen3.5-plus-20260420",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_6_27B,
      label: "qwen/qwen3.6-27b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_6_35B_A3B,
      label: "qwen/qwen3.6-35b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_6_FLASH,
      label: "qwen/qwen3.6-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_6_MAX_PREVIEW,
      label: "qwen/qwen3.6-max-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_6_PLUS,
      label: "qwen/qwen3.6-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_7_FLASH,
      label: "qwen/qwen3.7-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_7_MAX,
      label: "qwen/qwen3.7-max",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_7_PLUS,
      label: "qwen/qwen3.7-plus",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8_2_4T_A95B,
      label: "qwen/qwen3.8-2.4t-a95b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8_27B,
      label: "qwen/qwen3.8-27b",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8_FLASH,
      label: "qwen/qwen3.8-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8_MAX_0902,
      label: "qwen/qwen3.8-max-0902",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8_MAX_PRIME,
      label: "qwen/qwen3.8-max-prime",
    },
    {
      value: PROVIDER_MODEL_TYPE.QWEN_QWEN3_8_OMNI_FLASH,
      label: "qwen/qwen3.8-omni-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.REKAAI_REKA_EDGE,
      label: "rekaai/reka-edge",
    },
    {
      value: PROVIDER_MODEL_TYPE.REKAAI_REKA_FLASH_3,
      label: "rekaai/reka-flash-3",
    },
    {
      value: PROVIDER_MODEL_TYPE.RELACE_RELACE_APPLY_3,
      label: "relace/relace-apply-3",
    },
    {
      value: PROVIDER_MODEL_TYPE.RELACE_RELACE_SEARCH,
      label: "relace/relace-search",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAKANA_FUGU_MAX,
      label: "sakana/fugu-max",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAKANA_FUGU_ULTRA,
      label: "sakana/fugu-ultra",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAKANA_FUGU_ULTRA_V2,
      label: "sakana/fugu-ultra-v2",
    },
    {
      value: PROVIDER_MODEL_TYPE.SAKANA_SAKANA_NAMAZU,
      label: "sakana/sakana-namazu",
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
      value: PROVIDER_MODEL_TYPE.STEPFUN_STEP_3_5_FLASH,
      label: "stepfun/step-3.5-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.STEPFUN_STEP_3_7_FLASH,
      label: "stepfun/step-3.7-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HUNYUAN_A13B_INSTRUCT,
      label: "tencent/hunyuan-a13b-instruct",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HY_MT2_1_8B,
      label: "tencent/hy-mt2-1.8b",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HY_MT2_30B_A3B,
      label: "tencent/hy-mt2-30b-a3b",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HY_MT2_7B,
      label: "tencent/hy-mt2-7b",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HY3,
      label: "tencent/hy3",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HY3_PREVIEW,
      label: "tencent/hy3-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.TENCENT_HY4_PREVIEW,
      label: "tencent/hy4-preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.THEDRUMMER_CYDONIA_24B_V4_1,
      label: "thedrummer/cydonia-24b-v4.1",
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
      value: PROVIDER_MODEL_TYPE.THINKINGMACHINES_INKLING,
      label: "thinkingmachines/inkling",
    },
    {
      value: PROVIDER_MODEL_TYPE.THINKINGMACHINES_INKLING_SMALL,
      label: "thinkingmachines/inkling-small",
    },
    {
      value: PROVIDER_MODEL_TYPE.THINKINGMACHINES_INKLING_SMALL_FREE,
      label: "thinkingmachines/inkling-small:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.THINKINGMACHINES_INKLING_FREE,
      label: "thinkingmachines/inkling:free",
    },
    {
      value: PROVIDER_MODEL_TYPE.TYPESAFE_JEV_ROUTER,
      label: "typesafe/jev-router",
    },
    {
      value: PROVIDER_MODEL_TYPE.UNBIASED_PARETO,
      label: "unbiased/pareto",
    },
    {
      value: PROVIDER_MODEL_TYPE.UNDI95_REMM_SLERP_L2_13B,
      label: "undi95/remm-slerp-l2-13b",
    },
    {
      value: PROVIDER_MODEL_TYPE.UPSTAGE_SOLAR_MINI4,
      label: "upstage/solar-mini4",
    },
    {
      value: PROVIDER_MODEL_TYPE.UPSTAGE_SOLAR_PRO_3,
      label: "upstage/solar-pro-3",
    },
    {
      value: PROVIDER_MODEL_TYPE.UPSTAGE_SOLAR_PRO4,
      label: "upstage/solar-pro4",
    },
    {
      value: PROVIDER_MODEL_TYPE.WRITER_PALMYRA_X5,
      label: "writer/palmyra-x5",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_20,
      label: "x-ai/grok-4.20",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_20_MULTI_AGENT,
      label: "x-ai/grok-4.20-multi-agent",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_3,
      label: "x-ai/grok-4.3",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_5,
      label: "x-ai/grok-4.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_6,
      label: "x-ai/grok-4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_4_7,
      label: "x-ai/grok-4.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_BUILD_0_1,
      label: "x-ai/grok-build-0.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.XIAOMI_MIMO_V2_5,
      label: "xiaomi/mimo-v2.5",
    },
    {
      value: PROVIDER_MODEL_TYPE.XIAOMI_MIMO_V2_5_PRO,
      label: "xiaomi/mimo-v2.5-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.XIAOMI_MIMO_V2_6_FLASH,
      label: "xiaomi/mimo-v2.6-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.XIAOMI_MIMO_V2_6_PRO,
      label: "xiaomi/mimo-v2.6-pro",
    },
    {
      value: PROVIDER_MODEL_TYPE.XIAOMI_MIMO_V2_6_PRO_ULTRASPEED,
      label: "xiaomi/mimo-v2.6-pro-ultraspeed",
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
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5V,
      label: "z-ai/glm-4.5v",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_6,
      label: "z-ai/glm-4.6",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_6V,
      label: "z-ai/glm-4.6v",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_7,
      label: "z-ai/glm-4.7",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_4_7_FLASH,
      label: "z-ai/glm-4.7-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5,
      label: "z-ai/glm-5",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_TURBO,
      label: "z-ai/glm-5-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_1,
      label: "z-ai/glm-5.1",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_2,
      label: "z-ai/glm-5.2",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_3,
      label: "z-ai/glm-5.3",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_3_FLASH,
      label: "z-ai/glm-5.3-flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_3_FLASHX,
      label: "z-ai/glm-5.3-flashx",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5_3_PRIME,
      label: "z-ai/glm-5.3-prime",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_5V_TURBO,
      label: "z-ai/glm-5v-turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_FABLE_LATEST,
      label: "~anthropic/claude-fable-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_HAIKU_LATEST,
      label: "~anthropic/claude-haiku-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_OPUS_LATEST,
      label: "~anthropic/claude-opus-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_LATEST,
      label: "~anthropic/claude-sonnet-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_FLASH_LATEST,
      label: "~deepseek/deepseek-flash-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_PRO_LATEST,
      label: "~deepseek/deepseek-pro-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V4_FLASH_LATEST,
      label: "~deepseek/deepseek-v4-flash-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_FLASH_LATEST,
      label: "~google/gemini-flash-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.GOOGLE_GEMINI_PRO_LATEST,
      label: "~google/gemini-pro-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.MOONSHOTAI_KIMI_LATEST,
      label: "~moonshotai/kimi-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_ASTRA_LATEST,
      label: "~openai/gpt-astra-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_LUNA_LATEST,
      label: "~openai/gpt-luna-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_MINI_LATEST,
      label: "~openai/gpt-mini-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_SOL_LATEST,
      label: "~openai/gpt-sol-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.OPENAI_GPT_TERRA_LATEST,
      label: "~openai/gpt-terra-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.X_AI_GROK_LATEST,
      label: "~x-ai/grok-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_FLASH_LATEST,
      label: "~z-ai/glm-flash-latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.Z_AI_GLM_LATEST,
      label: "~z-ai/glm-latest",
    },
  ],

  [PROVIDER_TYPE.GEMINI]: [
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH,
      label: "Gemini 3.8 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH,
      label: "Gemini 3.7 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH,
      label: "Gemini 3.6 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH,
      label: "Gemini 3.5 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE,
      label: "Gemini 3.5 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_5_TRANSCRIBE,
      label: "Gemini 3.5 Transcribe",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO,
      label: "Gemini 3.1 Pro Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE,
      label: "Gemini 3.1 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      label: "Gemini 3 Flash Preview",
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
      label: "Gemini 2.5 Flash-Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMMA_4_26B_A4B_IT,
      label: "Gemma 4 26B A4B IT",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMMA_4_31B_IT,
      label: "Gemma 4 31B IT",
    },
    {
      value: PROVIDER_MODEL_TYPE.GEMINI_FLASH_LATEST_HIGH_RES_EXP,
      label: "Gemini Flash Latest High Res Exp",
    },
  ],

  [PROVIDER_TYPE.VERTEX_AI]: [
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH,
      label: "Gemini 3.8 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH,
      label: "Gemini 3.7 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_6_FLASH,
      label: "Gemini 3.6 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH,
      label: "Gemini 3.5 Flash",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
      label: "Gemini 3.5 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_PRO,
      label: "Gemini 3.1 Pro Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
      label: "Gemini 3.1 Flash Lite",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE_PREVIEW,
      label: "Gemini 3.1 Flash Lite Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO,
      label: "Gemini 3 Pro Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_FLASH_PREVIEW,
      label: "Gemini 3 Flash Preview",
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
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH,
      label: "Gemini 2.0 Flash 001",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_0_FLASH_LITE,
      label: "Gemini 2.0 Flash Lite 001",
    },
    {
      value: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_ROBOTICS_ER_2,
      label: "Gemini Robotics Er 2",
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
