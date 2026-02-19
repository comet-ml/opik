import { DropdownOption } from "@/types/shared";

export enum PROVIDER_TYPE {
  OPEN_AI = "openai",
  ANTHROPIC = "anthropic",
  OPEN_ROUTER = "openrouter",
  GEMINI = "gemini",
  VERTEX_AI = "vertex-ai",
  OLLAMA = "ollama",
  CUSTOM = "custom-llm",
  BEDROCK = "bedrock",
  OPIK_FREE = "opik-free",
}

export type COMPOSED_PROVIDER_TYPE = PROVIDER_TYPE | string;

export enum PROVIDER_MODEL_TYPE {
  // <------ opik free model
  OPIK_FREE_MODEL = "opik-free-model",

  // <------ openai
  GPT_4O = "gpt-4o",
  GPT_4O_MINI = "gpt-4o-mini",
  GPT_4O_MINI_2024_07_18 = "gpt-4o-mini-2024-07-18",
  GPT_4O_2024_08_06 = "gpt-4o-2024-08-06",
  GPT_4O_2024_05_13 = "gpt-4o-2024-05-13",
  GPT_4_1 = "gpt-4.1",
  GPT_4_1_MINI = "gpt-4.1-mini",
  GPT_4_1_NANO = "gpt-4.1-nano",
  GPT_5 = "gpt-5",
  GPT_5_MINI = "gpt-5-mini",
  GPT_5_NANO = "gpt-5-nano",
  GPT_5_CHAT_LATEST = "gpt-5-chat-latest",
  GPT_5_1 = "gpt-5.1",
  GPT_5_2 = "gpt-5.2",
  GPT_5_2_CHAT_LATEST = "gpt-5.2-chat-latest",
  GPT_4_TURBO = "gpt-4-turbo",
  GPT_4 = "gpt-4",
  GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview",
  GPT_4_TURBO_2024_04_09 = "gpt-4-turbo-2024-04-09",
  GPT_4_1106_PREVIEW = "gpt-4-1106-preview",
  GPT_4_0613 = "gpt-4-0613",
  GPT_4_0125_PREVIEW = "gpt-4-0125-preview",
  GPT_3_5_TURBO = "gpt-3.5-turbo",
  GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106",
  GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125",
  GPT_O1 = "o1",
  GPT_O1_MINI = "o1-mini",
  GPT_O3 = "o3",
  GPT_O3_MINI = "o3-mini",
  GPT_O4_MINI = "o4-mini",

  //  <----- anthropic
  CLAUDE_OPUS_4_6 = "claude-opus-4-6",
  CLAUDE_OPUS_4_5 = "claude-opus-4-5-20251101",
  CLAUDE_OPUS_4_1 = "claude-opus-4-1-20250805",
  CLAUDE_OPUS_4 = "claude-opus-4-20250514",
  CLAUDE_SONNET_4_6 = "claude-sonnet-4-6",
  CLAUDE_SONNET_4_5 = "claude-sonnet-4-5",
  CLAUDE_SONNET_4 = "claude-sonnet-4-20250514",
  CLAUDE_SONNET_3_7 = "claude-3-7-sonnet-20250219",
  CLAUDE_HAIKU_4_5 = "claude-haiku-4-5-20251001",
  CLAUDE_HAIKU_3_5 = "claude-haiku-4-5-20251001",
  CLAUDE_HAIKU_3 = "claude-3-haiku-20240307",
  CLAUDE_3_5_SONNET_20241022 = "claude-3-5-sonnet-20241022",
  CLAUDE_3_OPUS_20240229 = "claude-3-opus-20240229",

  //  <---- OpenRouter
  AI21_JAMBA_LARGE_1_7 = "ai21/jamba-large-1.7",
  AI21_JAMBA_MINI_1_7 = "ai21/jamba-mini-1.7",
  AION_LABS_AION_1_0 = "aion-labs/aion-1.0",
  AION_LABS_AION_1_0_MINI = "aion-labs/aion-1.0-mini",
  AION_LABS_AION_RP_LLAMA_3_1_8B = "aion-labs/aion-rp-llama-3.1-8b",
  ALFREDPROS_CODELLAMA_7B_INSTRUCT_SOLIDITY = "alfredpros/codellama-7b-instruct-solidity",
  ALIBABA_TONGYI_DEEPRESEARCH_30B_A3B = "alibaba/tongyi-deepresearch-30b-a3b",
  ALIBABA_TONGYI_DEEPRESEARCH_30B_A3B_FREE = "alibaba/tongyi-deepresearch-30b-a3b:free",
  ALLENAI_OLMO_2_0325_32B_INSTRUCT = "allenai/olmo-2-0325-32b-instruct",
  ALLENAI_OLMO_3_32B_THINK = "allenai/olmo-3-32b-think",
  ALLENAI_OLMO_3_7B_INSTRUCT = "allenai/olmo-3-7b-instruct",
  ALLENAI_OLMO_3_7B_THINK = "allenai/olmo-3-7b-think",
  ALPINDALE_GOLIATH_120B = "alpindale/goliath-120b",
  AMAZON_NOVA_LITE_V1 = "amazon/nova-lite-v1",
  AMAZON_NOVA_MICRO_V1 = "amazon/nova-micro-v1",
  AMAZON_NOVA_PREMIER_V1 = "amazon/nova-premier-v1",
  AMAZON_NOVA_PRO_V1 = "amazon/nova-pro-v1",
  ANTHRACITE_ORG_MAGNUM_V4_72B = "anthracite-org/magnum-v4-72b",
  ANTHROPIC_CLAUDE_3_HAIKU = "anthropic/claude-3-haiku",
  ANTHROPIC_CLAUDE_3_OPUS = "anthropic/claude-3-opus",
  ANTHROPIC_CLAUDE_3_5_HAIKU = "anthropic/claude-3.5-haiku",
  ANTHROPIC_CLAUDE_3_5_HAIKU_20241022 = "anthropic/claude-haiku-4.5",
  ANTHROPIC_CLAUDE_3_5_SONNET = "anthropic/claude-3.5-sonnet",
  ANTHROPIC_CLAUDE_3_7_SONNET = "anthropic/claude-3.7-sonnet",
  ANTHROPIC_CLAUDE_3_7_SONNET_THINKING = "anthropic/claude-3.7-sonnet:thinking",
  ANTHROPIC_CLAUDE_HAIKU_4_5 = "anthropic/claude-haiku-4.5",
  ANTHROPIC_CLAUDE_OPUS_4 = "anthropic/claude-opus-4",
  ANTHROPIC_CLAUDE_OPUS_4_1 = "anthropic/claude-opus-4.1",
  ANTHROPIC_CLAUDE_OPUS_4_5 = "anthropic/claude-opus-4.5",
  ANTHROPIC_CLAUDE_OPUS_4_6 = "anthropic/claude-opus-4.6",
  ANTHROPIC_CLAUDE_SONNET_4 = "anthropic/claude-sonnet-4",
  ANTHROPIC_CLAUDE_SONNET_4_5 = "anthropic/claude-sonnet-4.5",
  ANTHROPIC_CLAUDE_SONNET_4_6 = "anthropic/claude-sonnet-4.6",
  ARCEE_AI_AFM_4_5B = "arcee-ai/afm-4.5b",
  ARCEE_AI_CODER_LARGE = "arcee-ai/coder-large",
  ARCEE_AI_MAESTRO_REASONING = "arcee-ai/maestro-reasoning",
  ARCEE_AI_SPOTLIGHT = "arcee-ai/spotlight",
  ARCEE_AI_VIRTUOSO_LARGE = "arcee-ai/virtuoso-large",
  ARLIAI_QWQ_32B_ARLIAI_RPR_V1 = "arliai/qwq-32b-arliai-rpr-v1",
  ARLIAI_QWQ_32B_ARLIAI_RPR_V1_FREE = "arliai/qwq-32b-arliai-rpr-v1:free",
  BAIDU_ERNIE_4_5_21B_A3B = "baidu/ernie-4.5-21b-a3b",
  BAIDU_ERNIE_4_5_21B_A3B_THINKING = "baidu/ernie-4.5-21b-a3b-thinking",
  BAIDU_ERNIE_4_5_300B_A47B = "baidu/ernie-4.5-300b-a47b",
  BAIDU_ERNIE_4_5_VL_28B_A3B = "baidu/ernie-4.5-vl-28b-a3b",
  BAIDU_ERNIE_4_5_VL_424B_A47B = "baidu/ernie-4.5-vl-424b-a47b",
  BYTEDANCE_UI_TARS_1_5_7B = "bytedance/ui-tars-1.5-7b",
  COGNITIVECOMPUTATIONS_DOLPHIN_MISTRAL_24B_VENICE_EDITION_FREE = "cognitivecomputations/dolphin-mistral-24b-venice-edition:free",
  COHERE_COMMAND_A = "cohere/command-a",
  COHERE_COMMAND_R_08_2024 = "cohere/command-r-08-2024",
  COHERE_COMMAND_R_PLUS_08_2024 = "cohere/command-r-plus-08-2024",
  COHERE_COMMAND_R7B_12_2024 = "cohere/command-r7b-12-2024",
  DEEPCOGITO_COGITO_V2_PREVIEW_DEEPSEEK_671B = "deepcogito/cogito-v2-preview-deepseek-671b",
  DEEPCOGITO_COGITO_V2_PREVIEW_LLAMA_109B_MOE = "deepcogito/cogito-v2-preview-llama-109b-moe",
  DEEPCOGITO_COGITO_V2_PREVIEW_LLAMA_405B = "deepcogito/cogito-v2-preview-llama-405b",
  DEEPCOGITO_COGITO_V2_PREVIEW_LLAMA_70B = "deepcogito/cogito-v2-preview-llama-70b",
  DEEPCOGITO_COGITO_V2_1_671B = "deepcogito/cogito-v2.1-671b",
  DEEPSEEK_DEEPSEEK_CHAT = "deepseek/deepseek-chat",
  DEEPSEEK_DEEPSEEK_CHAT_V3_0324 = "deepseek/deepseek-chat-v3-0324",
  DEEPSEEK_DEEPSEEK_CHAT_V3_0324_FREE = "deepseek/deepseek-chat-v3-0324:free",
  DEEPSEEK_DEEPSEEK_CHAT_V3_1 = "deepseek/deepseek-chat-v3.1",
  DEEPSEEK_DEEPSEEK_PROVER_V2 = "deepseek/deepseek-prover-v2",
  DEEPSEEK_DEEPSEEK_R1 = "deepseek/deepseek-r1",
  DEEPSEEK_DEEPSEEK_R1_0528 = "deepseek/deepseek-r1-0528",
  DEEPSEEK_DEEPSEEK_R1_0528_QWEN3_8B = "deepseek/deepseek-r1-0528-qwen3-8b",
  DEEPSEEK_DEEPSEEK_R1_0528_QWEN3_8B_FREE = "deepseek/deepseek-r1-0528-qwen3-8b:free",
  DEEPSEEK_DEEPSEEK_R1_0528_FREE = "deepseek/deepseek-r1-0528:free",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_70B = "deepseek/deepseek-r1-distill-llama-70b",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_70B_FREE = "deepseek/deepseek-r1-distill-llama-70b:free",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_14B = "deepseek/deepseek-r1-distill-qwen-14b",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_32B = "deepseek/deepseek-r1-distill-qwen-32b",
  DEEPSEEK_DEEPSEEK_R1_FREE = "deepseek/deepseek-r1:free",
  DEEPSEEK_DEEPSEEK_V3_1_TERMINUS = "deepseek/deepseek-v3.1-terminus",
  DEEPSEEK_DEEPSEEK_V3_1_TERMINUS_EXACTO = "deepseek/deepseek-v3.1-terminus:exacto",
  DEEPSEEK_DEEPSEEK_V3_2_EXP = "deepseek/deepseek-v3.2-exp",
  ELEUTHERAI_LLEMMA_7B = "eleutherai/llemma_7b",
  GOOGLE_GEMINI_2_0_FLASH_001 = "google/gemini-2.0-flash-001",
  GOOGLE_GEMINI_2_0_FLASH_EXP_FREE = "google/gemini-2.0-flash-exp:free",
  GOOGLE_GEMINI_2_0_FLASH_LITE_001 = "google/gemini-2.0-flash-lite-001",
  GOOGLE_GEMINI_2_5_FLASH = "google/gemini-2.5-flash",
  GOOGLE_GEMINI_2_5_FLASH_IMAGE = "google/gemini-2.5-flash-image",
  GOOGLE_GEMINI_2_5_FLASH_IMAGE_PREVIEW = "google/gemini-2.5-flash-image-preview",
  GOOGLE_GEMINI_2_5_FLASH_LITE = "google/gemini-2.5-flash-lite",
  GOOGLE_GEMINI_2_5_FLASH_LITE_PREVIEW_09_2025 = "google/gemini-2.5-flash-lite-preview-09-2025",
  GOOGLE_GEMINI_2_5_FLASH_PREVIEW_09_2025 = "google/gemini-2.5-flash-preview-09-2025",
  GOOGLE_GEMINI_2_5_PRO = "google/gemini-2.5-pro",
  GOOGLE_GEMINI_2_5_PRO_PREVIEW = "google/gemini-2.5-pro-preview",
  GOOGLE_GEMINI_2_5_PRO_PREVIEW_05_06 = "google/gemini-2.5-pro-preview-05-06",
  GOOGLE_GEMINI_3_FLASH_PREVIEW = "google/gemini-3-flash-preview",
  GOOGLE_GEMINI_3_PRO_IMAGE_PREVIEW = "google/gemini-3-pro-image-preview",
  GOOGLE_GEMINI_3_PRO_PREVIEW = "google/gemini-3-pro-preview",
  GOOGLE_GEMMA_2_27B_IT = "google/gemma-2-27b-it",
  GOOGLE_GEMMA_2_9B_IT = "google/gemma-2-9b-it",
  GOOGLE_GEMMA_3_12B_IT = "google/gemma-3-12b-it",
  GOOGLE_GEMMA_3_12B_IT_FREE = "google/gemma-3-12b-it:free",
  GOOGLE_GEMMA_3_27B_IT = "google/gemma-3-27b-it",
  GOOGLE_GEMMA_3_27B_IT_FREE = "google/gemma-3-27b-it:free",
  GOOGLE_GEMMA_3_4B_IT = "google/gemma-3-4b-it",
  GOOGLE_GEMMA_3_4B_IT_FREE = "google/gemma-3-4b-it:free",
  GOOGLE_GEMMA_3N_E2B_IT_FREE = "google/gemma-3n-e2b-it:free",
  GOOGLE_GEMMA_3N_E4B_IT = "google/gemma-3n-e4b-it",
  GOOGLE_GEMMA_3N_E4B_IT_FREE = "google/gemma-3n-e4b-it:free",
  GRYPHE_MYTHOMAX_L2_13B = "gryphe/mythomax-l2-13b",
  IBM_GRANITE_GRANITE_4_0_H_MICRO = "ibm-granite/granite-4.0-h-micro",
  INCEPTION_MERCURY = "inception/mercury",
  INCEPTION_MERCURY_CODER = "inception/mercury-coder",
  INFLECTION_INFLECTION_3_PI = "inflection/inflection-3-pi",
  INFLECTION_INFLECTION_3_PRODUCTIVITY = "inflection/inflection-3-productivity",
  KWAIPILOT_KAT_CODER_PRO_FREE = "kwaipilot/kat-coder-pro:free",
  LIQUID_LFM_2_2_6B = "liquid/lfm-2.2-6b",
  LIQUID_LFM2_8B_A1B = "liquid/lfm2-8b-a1b",
  MANCER_WEAVER = "mancer/weaver",
  MEITUAN_LONGCAT_FLASH_CHAT = "meituan/longcat-flash-chat",
  MEITUAN_LONGCAT_FLASH_CHAT_FREE = "meituan/longcat-flash-chat:free",
  META_LLAMA_LLAMA_3_70B_INSTRUCT = "meta-llama/llama-3-70b-instruct",
  META_LLAMA_LLAMA_3_8B_INSTRUCT = "meta-llama/llama-3-8b-instruct",
  META_LLAMA_LLAMA_3_1_405B = "meta-llama/llama-3.1-405b",
  META_LLAMA_LLAMA_3_1_405B_INSTRUCT = "meta-llama/llama-3.1-405b-instruct",
  META_LLAMA_LLAMA_3_1_70B_INSTRUCT = "meta-llama/llama-3.1-70b-instruct",
  META_LLAMA_LLAMA_3_1_8B_INSTRUCT = "meta-llama/llama-3.1-8b-instruct",
  META_LLAMA_LLAMA_3_2_11B_VISION_INSTRUCT = "meta-llama/llama-3.2-11b-vision-instruct",
  META_LLAMA_LLAMA_3_2_1B_INSTRUCT = "meta-llama/llama-3.2-1b-instruct",
  META_LLAMA_LLAMA_3_2_3B_INSTRUCT = "meta-llama/llama-3.2-3b-instruct",
  META_LLAMA_LLAMA_3_2_3B_INSTRUCT_FREE = "meta-llama/llama-3.2-3b-instruct:free",
  META_LLAMA_LLAMA_3_2_90B_VISION_INSTRUCT = "meta-llama/llama-3.2-90b-vision-instruct",
  META_LLAMA_LLAMA_3_3_70B_INSTRUCT = "meta-llama/llama-3.3-70b-instruct",
  META_LLAMA_LLAMA_3_3_70B_INSTRUCT_FREE = "meta-llama/llama-3.3-70b-instruct:free",
  META_LLAMA_LLAMA_4_MAVERICK = "meta-llama/llama-4-maverick",
  META_LLAMA_LLAMA_4_SCOUT = "meta-llama/llama-4-scout",
  META_LLAMA_LLAMA_GUARD_2_8B = "meta-llama/llama-guard-2-8b",
  META_LLAMA_LLAMA_GUARD_3_8B = "meta-llama/llama-guard-3-8b",
  META_LLAMA_LLAMA_GUARD_4_12B = "meta-llama/llama-guard-4-12b",
  MICROSOFT_MAI_DS_R1 = "microsoft/mai-ds-r1",
  MICROSOFT_MAI_DS_R1_FREE = "microsoft/mai-ds-r1:free",
  MICROSOFT_PHI_3_MEDIUM_128K_INSTRUCT = "microsoft/phi-3-medium-128k-instruct",
  MICROSOFT_PHI_3_MINI_128K_INSTRUCT = "microsoft/phi-3-mini-128k-instruct",
  MICROSOFT_PHI_3_5_MINI_128K_INSTRUCT = "microsoft/phi-3.5-mini-128k-instruct",
  MICROSOFT_PHI_4 = "microsoft/phi-4",
  MICROSOFT_PHI_4_MULTIMODAL_INSTRUCT = "microsoft/phi-4-multimodal-instruct",
  MICROSOFT_PHI_4_REASONING_PLUS = "microsoft/phi-4-reasoning-plus",
  MICROSOFT_WIZARDLM_2_8X22B = "microsoft/wizardlm-2-8x22b",
  MINIMAX_MINIMAX_01 = "minimax/minimax-01",
  MINIMAX_MINIMAX_M1 = "minimax/minimax-m1",
  MINIMAX_MINIMAX_M2 = "minimax/minimax-m2",
  MISTRALAI_CODESTRAL_2501 = "mistralai/codestral-2501",
  MISTRALAI_CODESTRAL_2508 = "mistralai/codestral-2508",
  MISTRALAI_DEVSTRAL_MEDIUM = "mistralai/devstral-medium",
  MISTRALAI_DEVSTRAL_SMALL = "mistralai/devstral-small",
  MISTRALAI_DEVSTRAL_SMALL_2505 = "mistralai/devstral-small-2505",
  MISTRALAI_MAGISTRAL_MEDIUM_2506 = "mistralai/magistral-medium-2506",
  MISTRALAI_MAGISTRAL_MEDIUM_2506_THINKING = "mistralai/magistral-medium-2506:thinking",
  MISTRALAI_MAGISTRAL_SMALL_2506 = "mistralai/magistral-small-2506",
  MISTRALAI_MINISTRAL_3B = "mistralai/ministral-3b",
  MISTRALAI_MINISTRAL_8B = "mistralai/ministral-8b",
  MISTRALAI_MISTRAL_7B_INSTRUCT = "mistralai/mistral-7b-instruct",
  MISTRALAI_MISTRAL_7B_INSTRUCT_V0_1 = "mistralai/mistral-7b-instruct-v0.1",
  MISTRALAI_MISTRAL_7B_INSTRUCT_V0_2 = "mistralai/mistral-7b-instruct-v0.2",
  MISTRALAI_MISTRAL_7B_INSTRUCT_V0_3 = "mistralai/mistral-7b-instruct-v0.3",
  MISTRALAI_MISTRAL_7B_INSTRUCT_FREE = "mistralai/mistral-7b-instruct:free",
  MISTRALAI_MISTRAL_LARGE = "mistralai/mistral-large",
  MISTRALAI_MISTRAL_LARGE_2407 = "mistralai/mistral-large-2407",
  MISTRALAI_MISTRAL_LARGE_2411 = "mistralai/mistral-large-2411",
  MISTRALAI_MISTRAL_MEDIUM_3 = "mistralai/mistral-medium-3",
  MISTRALAI_MISTRAL_MEDIUM_3_1 = "mistralai/mistral-medium-3.1",
  MISTRALAI_MISTRAL_NEMO = "mistralai/mistral-nemo",
  MISTRALAI_MISTRAL_NEMO_FREE = "mistralai/mistral-nemo:free",
  MISTRALAI_MISTRAL_SABA = "mistralai/mistral-saba",
  MISTRALAI_MISTRAL_SMALL = "mistralai/mistral-small",
  MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501 = "mistralai/mistral-small-24b-instruct-2501",
  MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501_FREE = "mistralai/mistral-small-24b-instruct-2501:free",
  MISTRALAI_MISTRAL_SMALL_3_1_24B_INSTRUCT = "mistralai/mistral-small-3.1-24b-instruct",
  MISTRALAI_MISTRAL_SMALL_3_1_24B_INSTRUCT_FREE = "mistralai/mistral-small-3.1-24b-instruct:free",
  MISTRALAI_MISTRAL_SMALL_3_2_24B_INSTRUCT = "mistralai/mistral-small-3.2-24b-instruct",
  MISTRALAI_MISTRAL_SMALL_3_2_24B_INSTRUCT_FREE = "mistralai/mistral-small-3.2-24b-instruct:free",
  MISTRALAI_MISTRAL_TINY = "mistralai/mistral-tiny",
  MISTRALAI_MIXTRAL_8X22B_INSTRUCT = "mistralai/mixtral-8x22b-instruct",
  MISTRALAI_MIXTRAL_8X7B_INSTRUCT = "mistralai/mixtral-8x7b-instruct",
  MISTRALAI_PIXTRAL_12B = "mistralai/pixtral-12b",
  MISTRALAI_PIXTRAL_LARGE_2411 = "mistralai/pixtral-large-2411",
  MISTRALAI_VOXTRAL_SMALL_24B_2507 = "mistralai/voxtral-small-24b-2507",
  MOONSHOTAI_KIMI_DEV_72B = "moonshotai/kimi-dev-72b",
  MOONSHOTAI_KIMI_K2 = "moonshotai/kimi-k2",
  MOONSHOTAI_KIMI_K2_0905 = "moonshotai/kimi-k2-0905",
  MOONSHOTAI_KIMI_K2_0905_EXACTO = "moonshotai/kimi-k2-0905:exacto",
  MOONSHOTAI_KIMI_K2_THINKING = "moonshotai/kimi-k2-thinking",
  MOONSHOTAI_KIMI_K2_FREE = "moonshotai/kimi-k2:free",
  MOONSHOTAI_KIMI_LINEAR_48B_A3B_INSTRUCT = "moonshotai/kimi-linear-48b-a3b-instruct",
  MORPH_MORPH_V3_FAST = "morph/morph-v3-fast",
  MORPH_MORPH_V3_LARGE = "morph/morph-v3-large",
  NEVERSLEEP_LLAMA_3_1_LUMIMAID_8B = "neversleep/llama-3.1-lumimaid-8b",
  NEVERSLEEP_NOROMAID_20B = "neversleep/noromaid-20b",
  NOUSRESEARCH_DEEPHERMES_3_MISTRAL_24B_PREVIEW = "nousresearch/deephermes-3-mistral-24b-preview",
  NOUSRESEARCH_HERMES_2_PRO_LLAMA_3_8B = "nousresearch/hermes-2-pro-llama-3-8b",
  NOUSRESEARCH_HERMES_3_LLAMA_3_1_405B = "nousresearch/hermes-3-llama-3.1-405b",
  NOUSRESEARCH_HERMES_3_LLAMA_3_1_405B_FREE = "nousresearch/hermes-3-llama-3.1-405b:free",
  NOUSRESEARCH_HERMES_3_LLAMA_3_1_70B = "nousresearch/hermes-3-llama-3.1-70b",
  NOUSRESEARCH_HERMES_4_405B = "nousresearch/hermes-4-405b",
  NOUSRESEARCH_HERMES_4_70B = "nousresearch/hermes-4-70b",
  NVIDIA_LLAMA_3_1_NEMOTRON_70B_INSTRUCT = "nvidia/llama-3.1-nemotron-70b-instruct",
  NVIDIA_LLAMA_3_1_NEMOTRON_ULTRA_253B_V1 = "nvidia/llama-3.1-nemotron-ultra-253b-v1",
  NVIDIA_LLAMA_3_3_NEMOTRON_SUPER_49B_V1_5 = "nvidia/llama-3.3-nemotron-super-49b-v1.5",
  NVIDIA_NEMOTRON_NANO_12B_V2_VL = "nvidia/nemotron-nano-12b-v2-vl",
  NVIDIA_NEMOTRON_NANO_12B_V2_VL_FREE = "nvidia/nemotron-nano-12b-v2-vl:free",
  NVIDIA_NEMOTRON_NANO_9B_V2 = "nvidia/nemotron-nano-9b-v2",
  NVIDIA_NEMOTRON_NANO_9B_V2_FREE = "nvidia/nemotron-nano-9b-v2:free",
  OPENAI_CHATGPT_4O_LATEST = "openai/chatgpt-4o-latest",
  OPENAI_CODEX_MINI = "openai/codex-mini",
  OPENAI_GPT_3_5_TURBO = "openai/gpt-3.5-turbo",
  OPENAI_GPT_3_5_TURBO_0613 = "openai/gpt-3.5-turbo-0613",
  OPENAI_GPT_3_5_TURBO_16K = "openai/gpt-3.5-turbo-16k",
  OPENAI_GPT_3_5_TURBO_INSTRUCT = "openai/gpt-3.5-turbo-instruct",
  OPENAI_GPT_4 = "openai/gpt-4",
  OPENAI_GPT_4_0314 = "openai/gpt-4-0314",
  OPENAI_GPT_4_1106_PREVIEW = "openai/gpt-4-1106-preview",
  OPENAI_GPT_4_TURBO = "openai/gpt-4-turbo",
  OPENAI_GPT_4_TURBO_PREVIEW = "openai/gpt-4-turbo-preview",
  OPENAI_GPT_4_1 = "openai/gpt-4.1",
  OPENAI_GPT_4_1_MINI = "openai/gpt-4.1-mini",
  OPENAI_GPT_4_1_NANO = "openai/gpt-4.1-nano",
  OPENAI_GPT_4O = "openai/gpt-4o",
  OPENAI_GPT_4O_2024_05_13 = "openai/gpt-4o-2024-05-13",
  OPENAI_GPT_4O_2024_08_06 = "openai/gpt-4o-2024-08-06",
  OPENAI_GPT_4O_2024_11_20 = "openai/gpt-4o-2024-11-20",
  OPENAI_GPT_4O_AUDIO_PREVIEW = "openai/gpt-4o-audio-preview",
  OPENAI_GPT_4O_MINI = "openai/gpt-4o-mini",
  OPENAI_GPT_4O_MINI_2024_07_18 = "openai/gpt-4o-mini-2024-07-18",
  OPENAI_GPT_4O_MINI_SEARCH_PREVIEW = "openai/gpt-4o-mini-search-preview",
  OPENAI_GPT_4O_SEARCH_PREVIEW = "openai/gpt-4o-search-preview",
  OPENAI_GPT_4O_EXTENDED = "openai/gpt-4o:extended",
  OPENAI_GPT_5 = "openai/gpt-5",
  OPENAI_GPT_5_CHAT = "openai/gpt-5-chat",
  OPENAI_GPT_5_CODEX = "openai/gpt-5-codex",
  OPENAI_GPT_5_IMAGE = "openai/gpt-5-image",
  OPENAI_GPT_5_IMAGE_MINI = "openai/gpt-5-image-mini",
  OPENAI_GPT_5_MINI = "openai/gpt-5-mini",
  OPENAI_GPT_5_NANO = "openai/gpt-5-nano",
  OPENAI_GPT_5_PRO = "openai/gpt-5-pro",
  OPENAI_GPT_5_1 = "openai/gpt-5.1",
  OPENAI_GPT_5_1_CHAT = "openai/gpt-5.1-chat",
  OPENAI_GPT_5_1_CODEX = "openai/gpt-5.1-codex",
  OPENAI_GPT_5_1_CODEX_MINI = "openai/gpt-5.1-codex-mini",
  OPENAI_GPT_5_2 = "openai/gpt-5.2",
  OPENAI_GPT_5_2_CHAT_LATEST = "openai/gpt-5.2-chat-latest",
  OPENAI_GPT_OSS_120B = "openai/gpt-oss-120b",
  OPENAI_GPT_OSS_120B_EXACTO = "openai/gpt-oss-120b:exacto",
  OPENAI_GPT_OSS_20B = "openai/gpt-oss-20b",
  OPENAI_GPT_OSS_20B_FREE = "openai/gpt-oss-20b:free",
  OPENAI_GPT_OSS_SAFEGUARD_20B = "openai/gpt-oss-safeguard-20b",
  OPENAI_O1 = "openai/o1",
  OPENAI_O1_PRO = "openai/o1-pro",
  OPENAI_O3 = "openai/o3",
  OPENAI_O3_DEEP_RESEARCH = "openai/o3-deep-research",
  OPENAI_O3_MINI = "openai/o3-mini",
  OPENAI_O3_MINI_HIGH = "openai/o3-mini-high",
  OPENAI_O3_PRO = "openai/o3-pro",
  OPENAI_O4_MINI = "openai/o4-mini",
  OPENAI_O4_MINI_DEEP_RESEARCH = "openai/o4-mini-deep-research",
  OPENAI_O4_MINI_HIGH = "openai/o4-mini-high",
  OPENGVLAB_INTERNVL3_78B = "opengvlab/internvl3-78b",
  OPENROUTER_AUTO = "openrouter/auto",
  OPENROUTER_FREE = "openrouter/free",
  PERPLEXITY_SONAR = "perplexity/sonar",
  PERPLEXITY_SONAR_DEEP_RESEARCH = "perplexity/sonar-deep-research",
  PERPLEXITY_SONAR_PRO = "perplexity/sonar-pro",
  PERPLEXITY_SONAR_PRO_SEARCH = "perplexity/sonar-pro-search",
  PERPLEXITY_SONAR_REASONING = "perplexity/sonar-reasoning",
  PERPLEXITY_SONAR_REASONING_PRO = "perplexity/sonar-reasoning-pro",
  QWEN_QWEN_2_5_72B_INSTRUCT = "qwen/qwen-2.5-72b-instruct",
  QWEN_QWEN_2_5_72B_INSTRUCT_FREE = "qwen/qwen-2.5-72b-instruct:free",
  QWEN_QWEN_2_5_7B_INSTRUCT = "qwen/qwen-2.5-7b-instruct",
  QWEN_QWEN_2_5_CODER_32B_INSTRUCT = "qwen/qwen-2.5-coder-32b-instruct",
  QWEN_QWEN_2_5_CODER_32B_INSTRUCT_FREE = "qwen/qwen-2.5-coder-32b-instruct:free",
  QWEN_QWEN_2_5_VL_7B_INSTRUCT = "qwen/qwen-2.5-vl-7b-instruct",
  QWEN_QWEN_MAX = "qwen/qwen-max",
  QWEN_QWEN_PLUS = "qwen/qwen-plus",
  QWEN_QWEN_PLUS_2025_07_28 = "qwen/qwen-plus-2025-07-28",
  QWEN_QWEN_PLUS_2025_07_28_THINKING = "qwen/qwen-plus-2025-07-28:thinking",
  QWEN_QWEN_TURBO = "qwen/qwen-turbo",
  QWEN_QWEN_VL_MAX = "qwen/qwen-vl-max",
  QWEN_QWEN_VL_PLUS = "qwen/qwen-vl-plus",
  QWEN_QWEN2_5_CODER_7B_INSTRUCT = "qwen/qwen2.5-coder-7b-instruct",
  QWEN_QWEN2_5_VL_32B_INSTRUCT = "qwen/qwen2.5-vl-32b-instruct",
  QWEN_QWEN2_5_VL_32B_INSTRUCT_FREE = "qwen/qwen2.5-vl-32b-instruct:free",
  QWEN_QWEN2_5_VL_72B_INSTRUCT = "qwen/qwen2.5-vl-72b-instruct",
  QWEN_QWEN3_14B = "qwen/qwen3-14b",
  QWEN_QWEN3_14B_FREE = "qwen/qwen3-14b:free",
  QWEN_QWEN3_235B_A22B = "qwen/qwen3-235b-a22b",
  QWEN_QWEN3_235B_A22B_2507 = "qwen/qwen3-235b-a22b-2507",
  QWEN_QWEN3_235B_A22B_THINKING_2507 = "qwen/qwen3-235b-a22b-thinking-2507",
  QWEN_QWEN3_235B_A22B_FREE = "qwen/qwen3-235b-a22b:free",
  QWEN_QWEN3_30B_A3B = "qwen/qwen3-30b-a3b",
  QWEN_QWEN3_30B_A3B_INSTRUCT_2507 = "qwen/qwen3-30b-a3b-instruct-2507",
  QWEN_QWEN3_30B_A3B_THINKING_2507 = "qwen/qwen3-30b-a3b-thinking-2507",
  QWEN_QWEN3_30B_A3B_FREE = "qwen/qwen3-30b-a3b:free",
  QWEN_QWEN3_32B = "qwen/qwen3-32b",
  QWEN_QWEN3_4B_FREE = "qwen/qwen3-4b:free",
  QWEN_QWEN3_8B = "qwen/qwen3-8b",
  QWEN_QWEN3_CODER = "qwen/qwen3-coder",
  QWEN_QWEN3_CODER_30B_A3B_INSTRUCT = "qwen/qwen3-coder-30b-a3b-instruct",
  QWEN_QWEN3_CODER_FLASH = "qwen/qwen3-coder-flash",
  QWEN_QWEN3_CODER_PLUS = "qwen/qwen3-coder-plus",
  QWEN_QWEN3_CODER_EXACTO = "qwen/qwen3-coder:exacto",
  QWEN_QWEN3_CODER_FREE = "qwen/qwen3-coder:free",
  QWEN_QWEN3_MAX = "qwen/qwen3-max",
  QWEN_QWEN3_NEXT_80B_A3B_INSTRUCT = "qwen/qwen3-next-80b-a3b-instruct",
  QWEN_QWEN3_NEXT_80B_A3B_THINKING = "qwen/qwen3-next-80b-a3b-thinking",
  QWEN_QWEN3_VL_235B_A22B_INSTRUCT = "qwen/qwen3-vl-235b-a22b-instruct",
  QWEN_QWEN3_VL_235B_A22B_THINKING = "qwen/qwen3-vl-235b-a22b-thinking",
  QWEN_QWEN3_VL_30B_A3B_INSTRUCT = "qwen/qwen3-vl-30b-a3b-instruct",
  QWEN_QWEN3_VL_30B_A3B_THINKING = "qwen/qwen3-vl-30b-a3b-thinking",
  QWEN_QWEN3_VL_8B_INSTRUCT = "qwen/qwen3-vl-8b-instruct",
  QWEN_QWEN3_VL_8B_THINKING = "qwen/qwen3-vl-8b-thinking",
  QWEN_QWQ_32B = "qwen/qwq-32b",
  RAIFLE_SORCERERLM_8X22B = "raifle/sorcererlm-8x22b",
  RELACE_RELACE_APPLY_3 = "relace/relace-apply-3",
  SAO10K_L3_EURYALE_70B = "sao10k/l3-euryale-70b",
  SAO10K_L3_LUNARIS_8B = "sao10k/l3-lunaris-8b",
  SAO10K_L3_1_70B_HANAMI_X1 = "sao10k/l3.1-70b-hanami-x1",
  SAO10K_L3_1_EURYALE_70B = "sao10k/l3.1-euryale-70b",
  SAO10K_L3_3_EURYALE_70B = "sao10k/l3.3-euryale-70b",
  STEPFUN_AI_STEP3 = "stepfun-ai/step3",
  SWITCHPOINT_ROUTER = "switchpoint/router",
  TENCENT_HUNYUAN_A13B_INSTRUCT = "tencent/hunyuan-a13b-instruct",
  THEDRUMMER_ANUBIS_70B_V1_1 = "thedrummer/anubis-70b-v1.1",
  THEDRUMMER_CYDONIA_24B_V4_1 = "thedrummer/cydonia-24b-v4.1",
  THEDRUMMER_ROCINANTE_12B = "thedrummer/rocinante-12b",
  THEDRUMMER_SKYFALL_36B_V2 = "thedrummer/skyfall-36b-v2",
  THEDRUMMER_UNSLOPNEMO_12B = "thedrummer/unslopnemo-12b",
  THUDM_GLM_4_1V_9B_THINKING = "thudm/glm-4.1v-9b-thinking",
  TNGTECH_DEEPSEEK_R1T_CHIMERA = "tngtech/deepseek-r1t-chimera",
  TNGTECH_DEEPSEEK_R1T_CHIMERA_FREE = "tngtech/deepseek-r1t-chimera:free",
  TNGTECH_DEEPSEEK_R1T2_CHIMERA = "tngtech/deepseek-r1t2-chimera",
  TNGTECH_DEEPSEEK_R1T2_CHIMERA_FREE = "tngtech/deepseek-r1t2-chimera:free",
  UNDI95_REMM_SLERP_L2_13B = "undi95/remm-slerp-l2-13b",
  X_AI_GROK_3 = "x-ai/grok-3",
  X_AI_GROK_3_BETA = "x-ai/grok-3-beta",
  X_AI_GROK_3_MINI = "x-ai/grok-3-mini",
  X_AI_GROK_3_MINI_BETA = "x-ai/grok-3-mini-beta",
  X_AI_GROK_4 = "x-ai/grok-4",
  X_AI_GROK_4_FAST = "x-ai/grok-4-fast",
  X_AI_GROK_4_1_FAST = "x-ai/grok-4.1-fast",
  X_AI_GROK_4_1_FAST_FREE = "x-ai/grok-4.1-fast:free",
  X_AI_GROK_CODE_FAST_1 = "x-ai/grok-code-fast-1",
  Z_AI_GLM_4_32B = "z-ai/glm-4-32b",
  Z_AI_GLM_4_5 = "z-ai/glm-4.5",
  Z_AI_GLM_4_5_AIR = "z-ai/glm-4.5-air",
  Z_AI_GLM_4_5_AIR_FREE = "z-ai/glm-4.5-air:free",
  Z_AI_GLM_4_5V = "z-ai/glm-4.5v",
  Z_AI_GLM_4_6 = "z-ai/glm-4.6",
  Z_AI_GLM_4_6_EXACTO = "z-ai/glm-4.6:exacto",

  //   <----- gemini
  GEMINI_3_FLASH = "gemini-3-flash-preview",
  GEMINI_3_PRO = "gemini-3-pro-preview",
  GEMINI_2_0_FLASH = "gemini-2.0-flash-exp",
  GEMINI_2_0_FLASH_LITE = "gemini-2.0-flash-lite",
  GEMINI_1_5_FLASH = "gemini-1.5-flash",
  GEMINI_1_5_FLASH_8B = "gemini-1.5-flash-8b",
  GEMINI_1_5_PRO = "gemini-1.5-pro",
  GEMINI_2_5_PRO = "gemini-2.5-pro",
  GEMINI_2_5_FLASH = "gemini-2.5-flash",
  GEMINI_2_5_FLASH_LITE = "gemini-2.5-flash-lite",
  GEMINI_2_5_FLASH_LITE_PREVIEW_06_17 = "gemini-2.5-flash-lite-preview-06-17",

  //   <------ vertex ai
  VERTEX_AI_GEMINI_3_PRO = "vertex_ai/gemini-3-pro-preview",
  VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17 = "vertex_ai/gemini-2.5-flash-preview-04-17",
  VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_05_06 = "vertex_ai/gemini-2.5-pro-preview-05-06",
  GEMINI_2_5_PRO_PREVIEW_03_25 = "vertex_ai/gemini-2.5-pro-preview-03-25",
  GEMINI_2_5_PRO_EXP_03_25 = "vertex_ai/gemini-2.5-pro-exp-03-25",
  VERTEX_AI_GEMINI_2_0_FLASH = "vertex_ai/gemini-2.0-flash-001",
  VERTEX_AI_GEMINI_2_0_FLASH_LITE = "vertex_ai/gemini-2.0-flash-lite-001",
  VERTEX_AI_GEMINI_2_5_PRO = "vertex_ai/gemini-2.5-pro",
  VERTEX_AI_GEMINI_2_5_FLASH = "vertex_ai/gemini-2.5-flash",
  VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17 = "vertex_ai/gemini-2.5-flash-lite-preview-06-17",
}

export interface ProviderModelsMap {
  [providerKey: COMPOSED_PROVIDER_TYPE]: DropdownOption<PROVIDER_MODEL_TYPE>[];
}

export type PROVIDER_MODELS_TYPE = {
  [key in PROVIDER_TYPE]: {
    value: PROVIDER_MODEL_TYPE;
    label: string;
  }[];
};

export interface ProviderKeyConfiguration {
  location?: string;
  models?: string;
  /** For free model: the display label showing actual provider/model (e.g., "openai/gpt-4o-mini") */
  model_label?: string;
}

export interface BaseProviderKey {
  id: string;
  created_at: string;
  provider: PROVIDER_TYPE;
  ui_composed_provider: COMPOSED_PROVIDER_TYPE;
  configuration: ProviderKeyConfiguration;
  headers?: Record<string, string>;
  read_only: boolean;
}

export interface StandardProviderObject extends BaseProviderKey {
  provider: Exclude<PROVIDER_TYPE, PROVIDER_TYPE.CUSTOM | PROVIDER_TYPE.OLLAMA>;
  base_url?: never;
  provider_name?: never;
}

export interface CustomProviderObject extends BaseProviderKey {
  provider: PROVIDER_TYPE.CUSTOM;
  provider_name: string;
  base_url: string;
}

export interface OllamaProviderObject extends BaseProviderKey {
  provider: PROVIDER_TYPE.OLLAMA;
  provider_name: string;
  base_url: string;
}

export type ProviderObject =
  | StandardProviderObject
  | CustomProviderObject
  | OllamaProviderObject;

export type PartialProviderKeyUpdate = Partial<
  Omit<BaseProviderKey, "provider">
> & {
  id?: string;
  provider?: PROVIDER_TYPE;
  apiKey?: string;
  base_url?: string;
  provider_name?: string;
  headers?: Record<string, string>;
};

export type ReasoningEffort = "minimal" | "low" | "medium" | "high" | "xhigh";

export interface LLMOpenAIConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
  frequencyPenalty: number;
  presencePenalty: number;
  reasoningEffort?: ReasoningEffort;
  seed?: number | null;
  throttling?: number;
  maxConcurrentRequests?: number;
}

export type AnthropicThinkingEffort =
  | "adaptive"
  | "low"
  | "medium"
  | "high"
  | "max";

export interface LLMAnthropicConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP?: number;
  seed?: number | null;
  throttling?: number;
  maxConcurrentRequests?: number;
  thinkingEffort?: AnthropicThinkingEffort;
}

export interface LLMOpenRouterConfigsType {
  maxTokens: number;
  temperature: number;
  topP: number;
  topK: number;
  frequencyPenalty: number;
  presencePenalty: number;
  repetitionPenalty: number;
  minP: number;
  topA: number;
  seed?: number | null;
  throttling?: number;
  maxConcurrentRequests?: number;
}

export type GeminiThinkingLevel = "minimal" | "low" | "medium" | "high";

export interface LLMGeminiConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
  seed?: number | null;
  throttling?: number;
  maxConcurrentRequests?: number;
  thinkingLevel?: GeminiThinkingLevel;
}

export interface LLMVertexAIConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
  seed?: number | null;
  thinkingLevel?: GeminiThinkingLevel;
  throttling?: number;
  maxConcurrentRequests?: number;
}

export interface LLMCustomConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
  frequencyPenalty: number;
  presencePenalty: number;
  seed?: number | null;
  custom_parameters?: Record<string, unknown> | null;
  throttling?: number;
  maxConcurrentRequests?: number;
}

export type LLMPromptConfigsType =
  | Record<string, never>
  | LLMOpenAIConfigsType
  | LLMAnthropicConfigsType
  | LLMOpenRouterConfigsType
  | LLMGeminiConfigsType
  | LLMVertexAIConfigsType
  | LLMCustomConfigsType;
