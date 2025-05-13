export enum PROVIDER_TYPE {
  OPEN_AI = "openai",
  ANTHROPIC = "anthropic",
  OPEN_ROUTER = "openrouter",
  OLLAMA = "ollama",
  GEMINI = "gemini",
  VERTEX_AI = "vertex-ai",
}

export enum PROVIDER_MODEL_TYPE {
  // <------ openai
  GPT_4O = "gpt-4o",
  GPT_4O_MINI = "gpt-4o-mini",
  GPT_4O_MINI_2024_07_18 = "gpt-4o-mini-2024-07-18",
  GPT_4O_2024_08_06 = "gpt-4o-2024-08-06",
  GPT_4O_2024_05_13 = "gpt-4o-2024-05-13",
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

  //  <----- anthropic
  CLAUDE_3_5_SONNET_LATEST = "claude-3-5-sonnet-latest",
  CLAUDE_3_5_SONNET_20241022 = "claude-3-5-sonnet-20241022",
  CLAUDE_3_5_HAIKU_LATEST = "claude-3-5-haiku-latest",
  CLAUDE_3_5_HAIKU_20241022 = "claude-3-5-haiku-20241022",
  CLAUDE_3_5_SONNET_20240620 = "claude-3-5-sonnet-20240620",
  CLAUDE_3_OPUS_LATEST = "claude-3-opus-latest",
  CLAUDE_3_OPUS_20240229 = "claude-3-opus-20240229",
  CLAUDE_3_SONNET_20240229 = "claude-3-sonnet-20240229",
  CLAUDE_3_HAIKU_20240307 = "claude-3-haiku-20240307",

  //  <---- OpenRouter
  AETHERWIING_MN_STARCANNON_12B = "aetherwiing/mn-starcannon-12b",
  AI21_JAMBA_1_5_LARGE = "ai21/jamba-1-5-large",
  AI21_JAMBA_1_5_MINI = "ai21/jamba-1-5-mini",
  AI21_JAMBA_INSTRUCT = "ai21/jamba-instruct",
  AION_LABS_AION_1_0 = "aion-labs/aion-1.0",
  AION_LABS_AION_1_0_MINI = "aion-labs/aion-1.0-mini",
  AION_LABS_AION_RP_LLAMA_3_1_8B = "aion-labs/aion-rp-llama-3.1-8b",
  ALLENAI_LLAMA_3_1_TULU_3_405B = "allenai/llama-3.1-tulu-3-405b",
  ALLENAI_OLMO_7B_INSTRUCT = "allenai/olmo-7b-instruct",
  ALPINDALE_GOLIATH_120B = "alpindale/goliath-120b",
  ALPINDALE_MAGNUM_72B = "alpindale/magnum-72b",
  AMAZON_NOVA_LITE_V1 = "amazon/nova-lite-v1",
  AMAZON_NOVA_MICRO_V1 = "amazon/nova-micro-v1",
  AMAZON_NOVA_PRO_V1 = "amazon/nova-pro-v1",
  ANTHRACITE_ORG_MAGNUM_V2_72B = "anthracite-org/magnum-v2-72b",
  ANTHRACITE_ORG_MAGNUM_V4_72B = "anthracite-org/magnum-v4-72b",
  ANTHROPIC_CLAUDE_1 = "anthropic/claude-1",
  ANTHROPIC_CLAUDE_1_2 = "anthropic/claude-1.2",
  ANTHROPIC_CLAUDE_2 = "anthropic/claude-2",
  ANTHROPIC_CLAUDE_2_0 = "anthropic/claude-2.0",
  ANTHROPIC_CLAUDE_2_0_BETA = "anthropic/claude-2.0:beta",
  ANTHROPIC_CLAUDE_2_1 = "anthropic/claude-2.1",
  ANTHROPIC_CLAUDE_2_1_BETA = "anthropic/claude-2.1:beta",
  ANTHROPIC_CLAUDE_2_BETA = "anthropic/claude-2:beta",
  ANTHROPIC_CLAUDE_3_5_HAIKU = "anthropic/claude-3.5-haiku",
  ANTHROPIC_CLAUDE_3_5_HAIKU_20241022 = "anthropic/claude-3.5-haiku-20241022",
  ANTHROPIC_CLAUDE_3_5_HAIKU_20241022_BETA = "anthropic/claude-3.5-haiku-20241022:beta",
  ANTHROPIC_CLAUDE_3_5_HAIKU_BETA = "anthropic/claude-3.5-haiku:beta",
  ANTHROPIC_CLAUDE_3_5_SONNET = "anthropic/claude-3.5-sonnet",
  ANTHROPIC_CLAUDE_3_5_SONNET_20240620 = "anthropic/claude-3.5-sonnet-20240620",
  ANTHROPIC_CLAUDE_3_5_SONNET_20240620_BETA = "anthropic/claude-3.5-sonnet-20240620:beta",
  ANTHROPIC_CLAUDE_3_5_SONNET_BETA = "anthropic/claude-3.5-sonnet:beta",
  ANTHROPIC_CLAUDE_3_HAIKU = "anthropic/claude-3-haiku",
  ANTHROPIC_CLAUDE_3_HAIKU_BETA = "anthropic/claude-3-haiku:beta",
  ANTHROPIC_CLAUDE_3_OPUS = "anthropic/claude-3-opus",
  ANTHROPIC_CLAUDE_3_OPUS_BETA = "anthropic/claude-3-opus:beta",
  ANTHROPIC_CLAUDE_3_SONNET = "anthropic/claude-3-sonnet",
  ANTHROPIC_CLAUDE_3_SONNET_BETA = "anthropic/claude-3-sonnet:beta",
  ANTHROPIC_CLAUDE_INSTANT_1 = "anthropic/claude-instant-1",
  ANTHROPIC_CLAUDE_INSTANT_1_0 = "anthropic/claude-instant-1.0",
  ANTHROPIC_CLAUDE_INSTANT_1_1 = "anthropic/claude-instant-1.1",
  AUSTISM_CHRONOS_HERMES_13B = "austism/chronos-hermes-13b",
  BIGCODE_STARCODER2_15B_INSTRUCT = "bigcode/starcoder2-15b-instruct",
  COGNITIVECOMPUTATIONS_DOLPHIN3_0_MISTRAL_24B_FREE = "cognitivecomputations/dolphin3.0-mistral-24b:free",
  COGNITIVECOMPUTATIONS_DOLPHIN3_0_R1_MISTRAL_24B_FREE = "cognitivecomputations/dolphin3.0-r1-mistral-24b:free",
  COGNITIVECOMPUTATIONS_DOLPHIN_LLAMA_3_70B = "cognitivecomputations/dolphin-llama-3-70b",
  COGNITIVECOMPUTATIONS_DOLPHIN_MIXTRAL_8X22B = "cognitivecomputations/dolphin-mixtral-8x22b",
  COGNITIVECOMPUTATIONS_DOLPHIN_MIXTRAL_8X7B = "cognitivecomputations/dolphin-mixtral-8x7b",
  COHERE_COMMAND = "cohere/command",
  COHERE_COMMAND_R = "cohere/command-r",
  COHERE_COMMAND_R7B_12_2024 = "cohere/command-r7b-12-2024",
  COHERE_COMMAND_R_03_2024 = "cohere/command-r-03-2024",
  COHERE_COMMAND_R_08_2024 = "cohere/command-r-08-2024",
  COHERE_COMMAND_R_PLUS = "cohere/command-r-plus",
  COHERE_COMMAND_R_PLUS_04_2024 = "cohere/command-r-plus-04-2024",
  COHERE_COMMAND_R_PLUS_08_2024 = "cohere/command-r-plus-08-2024",
  DATABRICKS_DBRX_INSTRUCT = "databricks/dbrx-instruct",
  DEEPSEEK_DEEPSEEK_CHAT = "deepseek/deepseek-chat",
  DEEPSEEK_DEEPSEEK_CHAT_FREE = "deepseek/deepseek-chat:free",
  DEEPSEEK_DEEPSEEK_CHAT_V2_5 = "deepseek/deepseek-chat-v2.5",
  DEEPSEEK_DEEPSEEK_CODER = "deepseek/deepseek-coder",
  DEEPSEEK_DEEPSEEK_R1 = "deepseek/deepseek-r1",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_70B = "deepseek/deepseek-r1-distill-llama-70b",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_70B_FREE = "deepseek/deepseek-r1-distill-llama-70b:free",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_LLAMA_8B = "deepseek/deepseek-r1-distill-llama-8b",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_14B = "deepseek/deepseek-r1-distill-qwen-14b",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_1_5B = "deepseek/deepseek-r1-distill-qwen-1.5b",
  DEEPSEEK_DEEPSEEK_R1_DISTILL_QWEN_32B = "deepseek/deepseek-r1-distill-qwen-32b",
  DEEPSEEK_DEEPSEEK_R1_FREE = "deepseek/deepseek-r1:free",
  EVA_UNIT_01_EVA_LLAMA_3_33_70B = "eva-unit-01/eva-llama-3.33-70b",
  EVA_UNIT_01_EVA_QWEN_2_5_14B = "eva-unit-01/eva-qwen-2.5-14b",
  EVA_UNIT_01_EVA_QWEN_2_5_32B = "eva-unit-01/eva-qwen-2.5-32b",
  EVA_UNIT_01_EVA_QWEN_2_5_72B = "eva-unit-01/eva-qwen-2.5-72b",
  FIREWORKS_FIRELLAVA_13B = "fireworks/firellava-13b",
  GOOGLE_GEMINI_2_0_FLASH_001 = "google/gemini-2.0-flash-001",
  GOOGLE_GEMINI_2_0_FLASH_EXP_FREE = "google/gemini-2.0-flash-exp:free",
  GOOGLE_GEMINI_2_0_FLASH_LITE_PREVIEW_02_05_FREE = "google/gemini-2.0-flash-lite-preview-02-05:free",
  GOOGLE_GEMINI_2_0_FLASH_THINKING_EXP_1219_FREE = "google/gemini-2.0-flash-thinking-exp-1219:free",
  GOOGLE_GEMINI_2_0_FLASH_THINKING_EXP_FREE = "google/gemini-2.0-flash-thinking-exp:free",
  GOOGLE_GEMINI_2_0_PRO_EXP_02_05_FREE = "google/gemini-2.0-pro-exp-02-05:free",
  GOOGLE_GEMINI_EXP_1114 = "google/gemini-exp-1114",
  GOOGLE_GEMINI_EXP_1121 = "google/gemini-exp-1121",
  GOOGLE_GEMINI_EXP_1206_FREE = "google/gemini-exp-1206:free",
  GOOGLE_GEMINI_FLASH_1_5 = "google/gemini-flash-1.5",
  GOOGLE_GEMINI_FLASH_1_5_8B = "google/gemini-flash-1.5-8b",
  GOOGLE_GEMINI_FLASH_1_5_8B_EXP = "google/gemini-flash-1.5-8b-exp",
  GOOGLE_GEMINI_FLASH_1_5_EXP = "google/gemini-flash-1.5-exp",
  GOOGLE_GEMINI_PRO = "google/gemini-pro",
  GOOGLE_GEMINI_PRO_1_5 = "google/gemini-pro-1.5",
  GOOGLE_GEMINI_PRO_1_5_EXP = "google/gemini-pro-1.5-exp",
  GOOGLE_GEMINI_PRO_VISION = "google/gemini-pro-vision",
  GOOGLE_GEMMA_2_27B_IT = "google/gemma-2-27b-it",
  GOOGLE_GEMMA_2_9B_IT = "google/gemma-2-9b-it",
  GOOGLE_GEMMA_2_9B_IT_FREE = "google/gemma-2-9b-it:free",
  GOOGLE_GEMMA_7B_IT = "google/gemma-7b-it",
  GOOGLE_LEARNLM_1_5_PRO_EXPERIMENTAL_FREE = "google/learnlm-1.5-pro-experimental:free",
  GOOGLE_PALM_2_CHAT_BISON = "google/palm-2-chat-bison",
  GOOGLE_PALM_2_CHAT_BISON_32K = "google/palm-2-chat-bison-32k",
  GOOGLE_PALM_2_CODECHAT_BISON = "google/palm-2-codechat-bison",
  GOOGLE_PALM_2_CODECHAT_BISON_32K = "google/palm-2-codechat-bison-32k",
  GRYPHE_MYTHOMAX_L2_13B = "gryphe/mythomax-l2-13b",
  GRYPHE_MYTHOMAX_L2_13B_FREE = "gryphe/mythomax-l2-13b:free",
  GRYPHE_MYTHOMIST_7B = "gryphe/mythomist-7b",
  HUGGINGFACEH4_ZEPHYR_7B_BETA_FREE = "huggingfaceh4/zephyr-7b-beta:free",
  HUGGINGFACEH4_ZEPHYR_ORPO_141B_A35B = "huggingfaceh4/zephyr-orpo-141b-a35b",
  INFERMATIC_MN_INFEROR_12B = "infermatic/mn-inferor-12b",
  INFLATEBOT_MN_MAG_MELL_R1 = "inflatebot/mn-mag-mell-r1",
  INFLECTION_INFLECTION_3_PI = "inflection/inflection-3-pi",
  INFLECTION_INFLECTION_3_PRODUCTIVITY = "inflection/inflection-3-productivity",
  INTEL_NEURAL_CHAT_7B = "intel/neural-chat-7b",
  JEBCARTER_PSYFIGHTER_13B = "jebcarter/psyfighter-13b",
  JONDURBIN_AIROBOROS_L2_70B = "jondurbin/airoboros-l2-70b",
  JONDURBIN_BAGEL_34B = "jondurbin/bagel-34b",
  KOBOLDAI_PSYFIGHTER_13B_2 = "koboldai/psyfighter-13b-2",
  LIQUID_LFM_3B = "liquid/lfm-3b",
  LIQUID_LFM_40B = "liquid/lfm-40b",
  LIQUID_LFM_7B = "liquid/lfm-7b",
  LIUHAOTIAN_LLAVA_13B = "liuhaotian/llava-13b",
  LIUHAOTIAN_LLAVA_YI_34B = "liuhaotian/llava-yi-34b",
  LIZPRECIATIOR_LZLV_70B_FP16_HF = "lizpreciatior/lzlv-70b-fp16-hf",
  LYNN_SOLILOQUY_L3 = "lynn/soliloquy-l3",
  LYNN_SOLILOQUY_V3 = "lynn/soliloquy-v3",
  MANCER_WEAVER = "mancer/weaver",
  MATTSHUMER_REFLECTION_70B = "mattshumer/reflection-70b",
  META_LLAMA_CODELLAMA_34B_INSTRUCT = "meta-llama/codellama-34b-instruct",
  META_LLAMA_CODELLAMA_70B_INSTRUCT = "meta-llama/codellama-70b-instruct",
  META_LLAMA_LLAMA_2_13B_CHAT = "meta-llama/llama-2-13b-chat",
  META_LLAMA_LLAMA_2_70B_CHAT = "meta-llama/llama-2-70b-chat",
  META_LLAMA_LLAMA_3_1_405B = "meta-llama/llama-3.1-405b",
  META_LLAMA_LLAMA_3_1_405B_INSTRUCT = "meta-llama/llama-3.1-405b-instruct",
  META_LLAMA_LLAMA_3_1_70B_INSTRUCT = "meta-llama/llama-3.1-70b-instruct",
  META_LLAMA_LLAMA_3_1_8B_INSTRUCT = "meta-llama/llama-3.1-8b-instruct",
  META_LLAMA_LLAMA_3_2_11B_VISION_INSTRUCT = "meta-llama/llama-3.2-11b-vision-instruct",
  META_LLAMA_LLAMA_3_2_11B_VISION_INSTRUCT_FREE = "meta-llama/llama-3.2-11b-vision-instruct:free",
  META_LLAMA_LLAMA_3_2_1B_INSTRUCT = "meta-llama/llama-3.2-1b-instruct",
  META_LLAMA_LLAMA_3_2_3B_INSTRUCT = "meta-llama/llama-3.2-3b-instruct",
  META_LLAMA_LLAMA_3_2_90B_VISION_INSTRUCT = "meta-llama/llama-3.2-90b-vision-instruct",
  META_LLAMA_LLAMA_3_3_70B_INSTRUCT = "meta-llama/llama-3.3-70b-instruct",
  META_LLAMA_LLAMA_3_3_70B_INSTRUCT_FREE = "meta-llama/llama-3.3-70b-instruct:free",
  META_LLAMA_LLAMA_3_70B = "meta-llama/llama-3-70b",
  META_LLAMA_LLAMA_3_70B_INSTRUCT = "meta-llama/llama-3-70b-instruct",
  META_LLAMA_LLAMA_3_8B = "meta-llama/llama-3-8b",
  META_LLAMA_LLAMA_3_8B_INSTRUCT = "meta-llama/llama-3-8b-instruct",
  META_LLAMA_LLAMA_3_8B_INSTRUCT_FREE = "meta-llama/llama-3-8b-instruct:free",
  META_LLAMA_LLAMA_GUARD_2_8B = "meta-llama/llama-guard-2-8b",
  META_LLAMA_LLAMA_GUARD_3_8B = "meta-llama/llama-guard-3-8b",
  MICROSOFT_PHI_3_5_MINI_128K_INSTRUCT = "microsoft/phi-3.5-mini-128k-instruct",
  MICROSOFT_PHI_3_MEDIUM_128K_INSTRUCT = "microsoft/phi-3-medium-128k-instruct",
  MICROSOFT_PHI_3_MEDIUM_128K_INSTRUCT_FREE = "microsoft/phi-3-medium-128k-instruct:free",
  MICROSOFT_PHI_3_MEDIUM_4K_INSTRUCT = "microsoft/phi-3-medium-4k-instruct",
  MICROSOFT_PHI_3_MINI_128K_INSTRUCT = "microsoft/phi-3-mini-128k-instruct",
  MICROSOFT_PHI_3_MINI_128K_INSTRUCT_FREE = "microsoft/phi-3-mini-128k-instruct:free",
  MICROSOFT_PHI_4 = "microsoft/phi-4",
  MICROSOFT_WIZARDLM_2_7B = "microsoft/wizardlm-2-7b",
  MICROSOFT_WIZARDLM_2_8X22B = "microsoft/wizardlm-2-8x22b",
  MIGTISSERA_SYNTHIA_70B = "migtissera/synthia-70b",
  MINIMAX_MINIMAX_01 = "minimax/minimax-01",
  MISTRALAI_CODESTRAL_2501 = "mistralai/codestral-2501",
  MISTRALAI_CODESTRAL_MAMBA = "mistralai/codestral-mamba",
  MISTRALAI_MINISTRAL_3B = "mistralai/ministral-3b",
  MISTRALAI_MINISTRAL_8B = "mistralai/ministral-8b",
  MISTRALAI_MISTRAL_7B_INSTRUCT = "mistralai/mistral-7b-instruct",
  MISTRALAI_MISTRAL_7B_INSTRUCT_FREE = "mistralai/mistral-7b-instruct:free",
  MISTRALAI_MISTRAL_7B_INSTRUCT_V0_1 = "mistralai/mistral-7b-instruct-v0.1",
  MISTRALAI_MISTRAL_7B_INSTRUCT_V0_2 = "mistralai/mistral-7b-instruct-v0.2",
  MISTRALAI_MISTRAL_7B_INSTRUCT_V0_3 = "mistralai/mistral-7b-instruct-v0.3",
  MISTRALAI_MISTRAL_LARGE = "mistralai/mistral-large",
  MISTRALAI_MISTRAL_LARGE_2407 = "mistralai/mistral-large-2407",
  MISTRALAI_MISTRAL_LARGE_2411 = "mistralai/mistral-large-2411",
  MISTRALAI_MISTRAL_MEDIUM = "mistralai/mistral-medium",
  MISTRALAI_MISTRAL_NEMO = "mistralai/mistral-nemo",
  MISTRALAI_MISTRAL_NEMO_FREE = "mistralai/mistral-nemo:free",
  MISTRALAI_MISTRAL_SABA = "mistralai/mistral-saba",
  MISTRALAI_MISTRAL_SMALL = "mistralai/mistral-small",
  MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501 = "mistralai/mistral-small-24b-instruct-2501",
  MISTRALAI_MISTRAL_SMALL_24B_INSTRUCT_2501_FREE = "mistralai/mistral-small-24b-instruct-2501:free",
  MISTRALAI_MISTRAL_TINY = "mistralai/mistral-tiny",
  MISTRALAI_MIXTRAL_8X22B = "mistralai/mixtral-8x22b",
  MISTRALAI_MIXTRAL_8X22B_INSTRUCT = "mistralai/mixtral-8x22b-instruct",
  MISTRALAI_MIXTRAL_8X7B = "mistralai/mixtral-8x7b",
  MISTRALAI_MIXTRAL_8X7B_INSTRUCT = "mistralai/mixtral-8x7b-instruct",
  MISTRALAI_PIXTRAL_12B = "mistralai/pixtral-12b",
  MISTRALAI_PIXTRAL_LARGE_2411 = "mistralai/pixtral-large-2411",
  NEVERSLEEP_LLAMA_3_1_LUMIMAID_70B = "neversleep/llama-3.1-lumimaid-70b",
  NEVERSLEEP_LLAMA_3_1_LUMIMAID_8B = "neversleep/llama-3.1-lumimaid-8b",
  NEVERSLEEP_LLAMA_3_LUMIMAID_70B = "neversleep/llama-3-lumimaid-70b",
  NEVERSLEEP_LLAMA_3_LUMIMAID_8B = "neversleep/llama-3-lumimaid-8b",
  NEVERSLEEP_LLAMA_3_LUMIMAID_8B_EXTENDED = "neversleep/llama-3-lumimaid-8b:extended",
  NEVERSLEEP_NOROMAID_20B = "neversleep/noromaid-20b",
  NEVERSLEEP_NOROMAID_MIXTRAL_8X7B_INSTRUCT = "neversleep/noromaid-mixtral-8x7b-instruct",
  NOTHINGIISREAL_MN_CELESTE_12B = "nothingiisreal/mn-celeste-12b",
  NOUSRESEARCH_HERMES_2_PRO_LLAMA_3_8B = "nousresearch/hermes-2-pro-llama-3-8b",
  NOUSRESEARCH_HERMES_2_THETA_LLAMA_3_8B = "nousresearch/hermes-2-theta-llama-3-8b",
  NOUSRESEARCH_HERMES_3_LLAMA_3_1_405B = "nousresearch/hermes-3-llama-3.1-405b",
  NOUSRESEARCH_HERMES_3_LLAMA_3_1_70B = "nousresearch/hermes-3-llama-3.1-70b",
  NOUSRESEARCH_NOUS_CAPYBARA_34B = "nousresearch/nous-capybara-34b",
  NOUSRESEARCH_NOUS_CAPYBARA_7B = "nousresearch/nous-capybara-7b",
  NOUSRESEARCH_NOUS_HERMES_2_MISTRAL_7B_DPO = "nousresearch/nous-hermes-2-mistral-7b-dpo",
  NOUSRESEARCH_NOUS_HERMES_2_MIXTRAL_8X7B_DPO = "nousresearch/nous-hermes-2-mixtral-8x7b-dpo",
  NOUSRESEARCH_NOUS_HERMES_2_MIXTRAL_8X7B_SFT = "nousresearch/nous-hermes-2-mixtral-8x7b-sft",
  NOUSRESEARCH_NOUS_HERMES_2_VISION_7B = "nousresearch/nous-hermes-2-vision-7b",
  NOUSRESEARCH_NOUS_HERMES_LLAMA2_13B = "nousresearch/nous-hermes-llama2-13b",
  NOUSRESEARCH_NOUS_HERMES_LLAMA2_70B = "nousresearch/nous-hermes-llama2-70b",
  NOUSRESEARCH_NOUS_HERMES_YI_34B = "nousresearch/nous-hermes-yi-34b",
  NVIDIA_LLAMA_3_1_NEMOTRON_70B_INSTRUCT = "nvidia/llama-3.1-nemotron-70b-instruct",
  NVIDIA_LLAMA_3_1_NEMOTRON_70B_INSTRUCT_FREE = "nvidia/llama-3.1-nemotron-70b-instruct:free",
  NVIDIA_NEMOTRON_4_340B_INSTRUCT = "nvidia/nemotron-4-340b-instruct",
  OPENAI_CHATGPT_4O_LATEST = "openai/chatgpt-4o-latest",
  OPENAI_GPT_3_5_TURBO = "openai/gpt-3.5-turbo",
  OPENAI_GPT_3_5_TURBO_0125 = "openai/gpt-3.5-turbo-0125",
  OPENAI_GPT_3_5_TURBO_0301 = "openai/gpt-3.5-turbo-0301",
  OPENAI_GPT_3_5_TURBO_0613 = "openai/gpt-3.5-turbo-0613",
  OPENAI_GPT_3_5_TURBO_1106 = "openai/gpt-3.5-turbo-1106",
  OPENAI_GPT_3_5_TURBO_16K = "openai/gpt-3.5-turbo-16k",
  OPENAI_GPT_3_5_TURBO_INSTRUCT = "openai/gpt-3.5-turbo-instruct",
  OPENAI_GPT_4 = "openai/gpt-4",
  OPENAI_GPT_4O = "openai/gpt-4o",
  OPENAI_GPT_4O_2024_05_13 = "openai/gpt-4o-2024-05-13",
  OPENAI_GPT_4O_2024_08_06 = "openai/gpt-4o-2024-08-06",
  OPENAI_GPT_4O_2024_11_20 = "openai/gpt-4o-2024-11-20",
  OPENAI_GPT_4O_EXTENDED = "openai/gpt-4o:extended",
  OPENAI_GPT_4O_MINI = "openai/gpt-4o-mini",
  OPENAI_GPT_4O_MINI_2024_07_18 = "openai/gpt-4o-mini-2024-07-18",
  OPENAI_GPT_4_0314 = "openai/gpt-4-0314",
  OPENAI_GPT_4_1106_PREVIEW = "openai/gpt-4-1106-preview",
  OPENAI_GPT_4_32K = "openai/gpt-4-32k",
  OPENAI_GPT_4_32K_0314 = "openai/gpt-4-32k-0314",
  OPENAI_GPT_4_TURBO = "openai/gpt-4-turbo",
  OPENAI_GPT_4_TURBO_PREVIEW = "openai/gpt-4-turbo-preview",
  OPENAI_GPT_4_VISION_PREVIEW = "openai/gpt-4-vision-preview",
  OPENAI_O1 = "openai/o1",
  OPENAI_O1_MINI = "openai/o1-mini",
  OPENAI_O1_MINI_2024_09_12 = "openai/o1-mini-2024-09-12",
  OPENAI_O1_PREVIEW = "openai/o1-preview",
  OPENAI_O1_PREVIEW_2024_09_12 = "openai/o1-preview-2024-09-12",
  OPENAI_O3_MINI = "openai/o3-mini",
  OPENAI_O3_MINI_HIGH = "openai/o3-mini-high",
  OPENAI_SHAP_E = "openai/shap-e",
  OPENCHAT_OPENCHAT_7B = "openchat/openchat-7b",
  OPENCHAT_OPENCHAT_7B_FREE = "openchat/openchat-7b:free",
  OPENCHAT_OPENCHAT_8B = "openchat/openchat-8b",
  OPENROUTER_AUTO = "openrouter/auto",
  OPENROUTER_CINEMATIKA_7B = "openrouter/cinematika-7b",
  OPEN_ORCA_MISTRAL_7B_OPENORCA = "open-orca/mistral-7b-openorca",
  PERPLEXITY_LLAMA_3_1_SONAR_HUGE_128K_ONLINE = "perplexity/llama-3.1-sonar-huge-128k-online",
  PERPLEXITY_LLAMA_3_1_SONAR_LARGE_128K_CHAT = "perplexity/llama-3.1-sonar-large-128k-chat",
  PERPLEXITY_LLAMA_3_1_SONAR_LARGE_128K_ONLINE = "perplexity/llama-3.1-sonar-large-128k-online",
  PERPLEXITY_LLAMA_3_1_SONAR_SMALL_128K_CHAT = "perplexity/llama-3.1-sonar-small-128k-chat",
  PERPLEXITY_LLAMA_3_1_SONAR_SMALL_128K_ONLINE = "perplexity/llama-3.1-sonar-small-128k-online",
  PERPLEXITY_LLAMA_3_SONAR_LARGE_32K_CHAT = "perplexity/llama-3-sonar-large-32k-chat",
  PERPLEXITY_LLAMA_3_SONAR_LARGE_32K_ONLINE = "perplexity/llama-3-sonar-large-32k-online",
  PERPLEXITY_LLAMA_3_SONAR_SMALL_32K_CHAT = "perplexity/llama-3-sonar-small-32k-chat",
  PERPLEXITY_LLAMA_3_SONAR_SMALL_32K_ONLINE = "perplexity/llama-3-sonar-small-32k-online",
  PERPLEXITY_SONAR = "perplexity/sonar",
  PERPLEXITY_SONAR_REASONING = "perplexity/sonar-reasoning",
  PHIND_PHIND_CODELLAMA_34B = "phind/phind-codellama-34b",
  PYGMALIONAI_MYTHALION_13B = "pygmalionai/mythalion-13b",
  QWEN_QVQ_72B_PREVIEW = "qwen/qvq-72b-preview",
  QWEN_QWEN2_5_VL_72B_INSTRUCT_FREE = "qwen/qwen2.5-vl-72b-instruct:free",
  QWEN_QWEN_110B_CHAT = "qwen/qwen-110b-chat",
  QWEN_QWEN_14B_CHAT = "qwen/qwen-14b-chat",
  QWEN_QWEN_2_5_72B_INSTRUCT = "qwen/qwen-2.5-72b-instruct",
  QWEN_QWEN_2_5_7B_INSTRUCT = "qwen/qwen-2.5-7b-instruct",
  QWEN_QWEN_2_5_CODER_32B_INSTRUCT = "qwen/qwen-2.5-coder-32b-instruct",
  QWEN_QWEN_2_72B_INSTRUCT = "qwen/qwen-2-72b-instruct",
  QWEN_QWEN_2_7B_INSTRUCT = "qwen/qwen-2-7b-instruct",
  QWEN_QWEN_2_VL_72B_INSTRUCT = "qwen/qwen-2-vl-72b-instruct",
  QWEN_QWEN_2_VL_7B_INSTRUCT = "qwen/qwen-2-vl-7b-instruct",
  QWEN_QWEN_32B_CHAT = "qwen/qwen-32b-chat",
  QWEN_QWEN_4B_CHAT = "qwen/qwen-4b-chat",
  QWEN_QWEN_72B_CHAT = "qwen/qwen-72b-chat",
  QWEN_QWEN_7B_CHAT = "qwen/qwen-7b-chat",
  QWEN_QWEN_MAX = "qwen/qwen-max",
  QWEN_QWEN_PLUS = "qwen/qwen-plus",
  QWEN_QWEN_TURBO = "qwen/qwen-turbo",
  QWEN_QWEN_VL_PLUS_FREE = "qwen/qwen-vl-plus:free",
  QWEN_QWQ_32B_PREVIEW = "qwen/qwq-32b-preview",
  RAIFLE_SORCERERLM_8X22B = "raifle/sorcererlm-8x22b",
  RECURSAL_EAGLE_7B = "recursal/eagle-7b",
  RECURSAL_RWKV_5_3B_AI_TOWN = "recursal/rwkv-5-3b-ai-town",
  RWKV_RWKV_5_WORLD_3B = "rwkv/rwkv-5-world-3b",
  SAO10K_FIMBULVETR_11B_V2 = "sao10k/fimbulvetr-11b-v2",
  SAO10K_L3_1_70B_HANAMI_X1 = "sao10k/l3.1-70b-hanami-x1",
  SAO10K_L3_1_EURYALE_70B = "sao10k/l3.1-euryale-70b",
  SAO10K_L3_3_EURYALE_70B = "sao10k/l3.3-euryale-70b",
  SAO10K_L3_EURYALE_70B = "sao10k/l3-euryale-70b",
  SAO10K_L3_LUNARIS_8B = "sao10k/l3-lunaris-8b",
  SAO10K_L3_STHENO_8B = "sao10k/l3-stheno-8b",
  SNOWFLAKE_SNOWFLAKE_ARCTIC_INSTRUCT = "snowflake/snowflake-arctic-instruct",
  SOPHOSYMPATHEIA_MIDNIGHT_ROSE_70B = "sophosympatheia/midnight-rose-70b",
  SOPHOSYMPATHEIA_ROGUE_ROSE_103B_V0_2_FREE = "sophosympatheia/rogue-rose-103b-v0.2:free",
  TEKNIUM_OPENHERMES_2_5_MISTRAL_7B = "teknium/openhermes-2.5-mistral-7b",
  TEKNIUM_OPENHERMES_2_MISTRAL_7B = "teknium/openhermes-2-mistral-7b",
  THEDRUMMER_ROCINANTE_12B = "thedrummer/rocinante-12b",
  THEDRUMMER_UNSLOPNEMO_12B = "thedrummer/unslopnemo-12b",
  TOGETHERCOMPUTER_STRIPEDHYENA_HESSIAN_7B = "togethercomputer/stripedhyena-hessian-7b",
  TOGETHERCOMPUTER_STRIPEDHYENA_NOUS_7B = "togethercomputer/stripedhyena-nous-7b",
  UNDI95_REMM_SLERP_L2_13B = "undi95/remm-slerp-l2-13b",
  UNDI95_TOPPY_M_7B = "undi95/toppy-m-7b",
  UNDI95_TOPPY_M_7B_FREE = "undi95/toppy-m-7b:free",
  XWIN_LM_XWIN_LM_70B = "xwin-lm/xwin-lm-70b",
  X_AI_GROK_2 = "x-ai/grok-2",
  X_AI_GROK_2_1212 = "x-ai/grok-2-1212",
  X_AI_GROK_2_MINI = "x-ai/grok-2-mini",
  X_AI_GROK_2_VISION_1212 = "x-ai/grok-2-vision-1212",
  X_AI_GROK_BETA = "x-ai/grok-beta",
  X_AI_GROK_VISION_BETA = "x-ai/grok-vision-beta",
  ZERO_ONE_AI_YI_1_5_34B_CHAT = "01-ai/yi-1.5-34b-chat",
  ZERO_ONE_AI_YI_34B = "01-ai/yi-34b",
  ZERO_ONE_AI_YI_34B_200K = "01-ai/yi-34b-200k",
  ZERO_ONE_AI_YI_34B_CHAT = "01-ai/yi-34b-chat",
  ZERO_ONE_AI_YI_6B = "01-ai/yi-6b",
  ZERO_ONE_AI_YI_LARGE = "01-ai/yi-large",
  ZERO_ONE_AI_YI_LARGE_FC = "01-ai/yi-large-fc",
  ZERO_ONE_AI_YI_LARGE_TURBO = "01-ai/yi-large-turbo",
  ZERO_ONE_AI_YI_VISION = "01-ai/yi-vision",

  //   <----- gemini
  GEMINI_2_0_FLASH = "gemini-2.0-flash-exp",
  GEMINI_1_5_FLASH = "gemini-1.5-flash",
  GEMINI_1_5_FLASH_8B = "gemini-1.5-flash-8b",
  GEMINI_1_5_PRO = "gemini-1.5-pro",

  //   <------ vertex ai
  VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_04_17 = "vertex_ai/gemini-2.5-flash-preview-04-17",
  VERTEX_AI_GEMINI_2_5_PRO_PREVIEW_05_06 = "vertex_ai/gemini-2.5-pro-preview-05-06",
  GEMINI_2_5_PRO_PREVIEW_03_25 = "vertex_ai/gemini-2.5-pro-preview-03-25",
  GEMINI_2_5_PRO_EXP_03_25 = "vertex_ai/gemini-2.5-pro-exp-03-25",
  VERTEX_AI_GEMINI_2_0_FLASH = "vertex_ai/gemini-2.0-flash-001",
  VERTEX_AI_GEMINI_2_0_FLASH_LITE = "vertex_ai/gemini-2.0-flash-lite-001",
}

export type PROVIDER_MODELS_TYPE = {
  [key in PROVIDER_TYPE]: {
    value: PROVIDER_MODEL_TYPE;
    label: string;
    structuredOutput?: boolean;
  }[];
};

export enum PROVIDER_LOCATION_TYPE {
  cloud = "cloud",
  local = "local",
}

export interface LocalAIProviderData {
  url: string;
  models: string;
  created_at: string;
}

export interface ProviderKey {
  id: string;
  keyName: string;
  created_at: string;
  provider: PROVIDER_TYPE;
}

export interface ProviderKeyWithAPIKey extends ProviderKey {
  apiKey: string;
  location?: string;
}

export interface LLMOpenAIConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
  frequencyPenalty: number;
  presencePenalty: number;
}

export interface LLMAnthropicConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
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
}

export interface LLMGeminiConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
}

export interface LLMVertexAIConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
}

export type LLMPromptConfigsType =
  | Record<string, never>
  | LLMOpenAIConfigsType
  | LLMAnthropicConfigsType
  | LLMOpenRouterConfigsType
  | LLMGeminiConfigsType
  | LLMVertexAIConfigsType;
