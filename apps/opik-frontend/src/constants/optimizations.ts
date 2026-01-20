import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  Optimization,
} from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";

export const OPTIMIZATION_STUDIO_SUPPORTED_MODELS: Record<
  PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE[]
> = {
  [PROVIDER_TYPE.OPEN_AI]: [
    PROVIDER_MODEL_TYPE.GPT_5_2,
    PROVIDER_MODEL_TYPE.GPT_5_1,
    PROVIDER_MODEL_TYPE.GPT_5_MINI,
    PROVIDER_MODEL_TYPE.GPT_5_NANO,
    PROVIDER_MODEL_TYPE.GPT_4O,
    PROVIDER_MODEL_TYPE.GPT_4O_MINI,
    PROVIDER_MODEL_TYPE.GPT_4_1,
    PROVIDER_MODEL_TYPE.GPT_4_1_MINI,
    PROVIDER_MODEL_TYPE.GPT_4_1_NANO,
  ],
  [PROVIDER_TYPE.ANTHROPIC]: [
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4,
    PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
    PROVIDER_MODEL_TYPE.CLAUDE_SONNET_3_7,
    PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_3_5,
  ],
  [PROVIDER_TYPE.OPEN_ROUTER]: [],
  [PROVIDER_TYPE.GEMINI]: [],
  [PROVIDER_TYPE.VERTEX_AI]: [],
  [PROVIDER_TYPE.CUSTOM]: [],
  [PROVIDER_TYPE.BEDROCK]: [],
  [PROVIDER_TYPE.OPIK_FREE]: [],
};

export const DEFAULT_GEPA_OPTIMIZER_CONFIGS = {
  VERBOSE: false,
  SEED: 42,
};

export const DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS = {
  POPULATION_SIZE: 10,
  NUM_GENERATIONS: 5,
  MUTATION_RATE: 0.1,
  CROSSOVER_RATE: 0.7,
  TOURNAMENT_SIZE: 3,
  ELITISM_SIZE: 1,
  ADAPTIVE_MUTATION: false,
  ENABLE_MOO: false,
  ENABLE_LLM_CROSSOVER: false,
  OUTPUT_STYLE_GUIDANCE: "",
  INFER_OUTPUT_STYLE: false,
  N_THREADS: 1,
  VERBOSE: false,
  SEED: 42,
};

export const DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS = {
  CONVERGENCE_THRESHOLD: 0.01,
  VERBOSE: false,
  SEED: 42,
};

export const DEFAULT_EQUALS_METRIC_CONFIGS = {
  CASE_SENSITIVE: false,
  REFERENCE_KEY: "",
};

export const DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS = {
  REFERENCE_KEY: "",
  CASE_SENSITIVE: false,
};

export const DEFAULT_G_EVAL_METRIC_CONFIGS = {
  TASK_INTRODUCTION:
    "You are evaluating how well an AI assistant answers questions based on context.",
  EVALUATION_CRITERIA:
    "The answer should be accurate, relevant, and directly address the question. Consider: 1) Factual correctness, 2) Completeness of the answer, 3) Clarity and coherence",
};

export const DEFAULT_LEVENSHTEIN_METRIC_CONFIGS = {
  CASE_SENSITIVE: false,
  REFERENCE_KEY: "",
};

export const OPTIMIZATION_MESSAGE_TYPE_OPTIONS = [
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.system],
    value: LLM_MESSAGE_ROLE.system,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.user],
    value: LLM_MESSAGE_ROLE.user,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.assistant],
    value: LLM_MESSAGE_ROLE.assistant,
  },
];

export const OPTIMIZER_OPTIONS = [
  {
    value: OPTIMIZER_TYPE.GEPA,
    label: "GEPA optimizer",
    description: "Tries many prompt variations and keeps the best ones.",
  },
  {
    value: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE,
    label: "Hierarchical Reflective",
    description: "Analyzes failures and rewrites prompts to fix issues.",
  },
];

export const OPTIMIZATION_METRIC_OPTIONS = [
  {
    value: METRIC_TYPE.EQUALS,
    label: "Equals",
    description: "Checks for an exact match with the expected output.",
  },
  {
    value: METRIC_TYPE.JSON_SCHEMA_VALIDATOR,
    label: "JSON Schema Validator",
    description: "Validates output structure against a JSON schema.",
  },
  {
    value: METRIC_TYPE.G_EVAL,
    label: "Custom (G-Eval)",
    description: "Uses an LLM to score outputs with custom criteria.",
  },
  {
    value: METRIC_TYPE.LEVENSHTEIN,
    label: "Levenshtein",
    description: "Measures edit distance between output and expected text.",
  },
];

// Type for demo dataset items - keys should match template variables
export type DemoDatasetItem = Record<string, unknown>;

export type OptimizationTemplate = Partial<Optimization> & {
  id: string;
  title: string;
  description: string;
  dataset_items?: DemoDatasetItem[];
};

// Chatbot training dataset items - mix of Opik questions (should answer) and off-topic (should decline)
const CHATBOT_DATASET_ITEMS: DemoDatasetItem[] = [
  {
    message: "Is it normal for the app to crash 5 times a day? Just curious.",
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message: "Love the product! Would be even better with dark mode though.",
    expected_intent: "request",
    expected_urgency: "low",
  },
  {
    message:
      "Thanks for the 'update' that broke everything. Really appreciate it.",
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message:
      "Anyone else having issues? Our whole team can't access anything and we have a client demo in 2 hours.",
    expected_intent: "complaint",
    expected_urgency: "critical",
  },
  {
    message:
      "The new pricing seems expensive compared to competitors. What do I get for the extra cost?",
    expected_intent: "question",
    expected_urgency: "medium",
  },
  {
    message:
      "No rush, but I've been waiting 3 weeks for a response to my support ticket. Just following up when you get a chance.",
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message:
      "Can someone please help me recover my data? I lost everything after your last update and I'm panicking.",
    expected_intent: "request",
    expected_urgency: "critical",
  },
  {
    message: "Interesting approach with the new UI. Different.",
    expected_intent: "feedback",
    expected_urgency: "low",
  },
  {
    message: "Why did you remove the export feature? I used it every day.",
    expected_intent: "complaint",
    expected_urgency: "medium",
  },
  {
    message:
      "Hey, quick thing - need to add 50 users to our enterprise account before tomorrow's onboarding. How do I do that?",
    expected_intent: "request",
    expected_urgency: "critical",
  },
  {
    message:
      "Wow, only 10 minutes to load a simple report. Impressive performance optimization!",
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message:
      "Just checking - what's the process for exporting all my data? Asking for future reference.",
    expected_intent: "request",
    expected_urgency: "medium",
  },
];

const EXPECTED_JSON_SCHEMA = {
  type: "object",
  properties: {
    intent: {
      type: "object",
      properties: {
        primary: {
          type: "string",
          enum: ["question", "complaint", "request", "feedback", "other"],
        },
        confidence: { type: "number" },
      },
      required: ["primary", "confidence"],
    },
    urgency: {
      type: "string",
      enum: ["critical", "high", "medium", "low"],
    },
    sentiment: {
      type: "string",
      enum: ["positive", "neutral", "negative"],
    },
    entities: {
      type: "object",
      properties: {
        person_name: { type: ["string", "null"] },
        product: { type: ["string", "null"] },
        issue_type: { type: ["string", "null"] },
      },
    },
    action: {
      type: "object",
      properties: {
        type: {
          type: "string",
          enum: ["escalate", "respond", "investigate", "close"],
        },
        team: {
          type: "string",
          enum: ["support", "engineering", "billing", "sales"],
        },
        notes: { type: "string" },
      },
      required: ["type", "team"],
    },
    response: {
      type: "object",
      properties: {
        tone: {
          type: "string",
          enum: ["empathetic", "professional", "apologetic"],
        },
        key_points: {
          type: "array",
          items: { type: "string" },
        },
      },
      required: ["tone", "key_points"],
    },
  },
  required: ["intent", "urgency", "sentiment", "action", "response"],
};

// JSON output dataset items - tasks to generate structured JSON data
const JSON_OUTPUT_DATASET_ITEMS: DemoDatasetItem[] = [
  {
    message: "Is it normal for the app to crash 5 times a day? Just curious.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message: "Love the product! Would be even better with dark mode though.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "request",
    expected_urgency: "low",
  },
  {
    message:
      "Thanks for the 'update' that broke everything. Really appreciate it.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message:
      "Anyone else having issues? Our whole team can't access anything and we have a client demo in 2 hours.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "complaint",
    expected_urgency: "critical",
  },
  {
    message:
      "The new pricing seems expensive compared to competitors. What do I get for the extra cost?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "question",
    expected_urgency: "medium",
  },
  {
    message:
      "No rush, but I've been waiting 3 weeks for a response to my support ticket. Just following up when you get a chance.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message:
      "Can someone please help me recover my data? I lost everything after your last update and I'm panicking.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "request",
    expected_urgency: "critical",
  },
  {
    message: "Interesting approach with the new UI. Different.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "feedback",
    expected_urgency: "low",
  },
  {
    message: "Why did you remove the export feature? I used it every day.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "complaint",
    expected_urgency: "medium",
  },
  {
    message:
      "Hey, quick thing - need to add 50 users to our enterprise account before tomorrow's onboarding. How do I do that?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "request",
    expected_urgency: "critical",
  },
  {
    message:
      "Wow, only 10 minutes to load a simple report. Impressive performance optimization!",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "complaint",
    expected_urgency: "high",
  },
  {
    message:
      "Just checking - what's the process for exporting all my data? Asking for future reference.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_intent: "request",
    expected_urgency: "medium",
  },
];

export const OPTIMIZATION_DEMO_TEMPLATES: OptimizationTemplate[] = [
  {
    id: "opik-chatbot",
    title: "Demo template - Message Classifier",
    description: "Classify messages by intent and urgency for support routing",
    name: "Message classifier optimization",
    dataset_id: "",
    dataset_items: CHATBOT_DATASET_ITEMS,
    studio_config: {
      dataset_name: "Demo - Customer Message Classifier",
      prompt: {
        messages: [
          {
            role: LLM_MESSAGE_ROLE.system,
            content: `Classify customer messages. Output JSON with:
{
  "intent": {"primary": "question|complaint|request|feedback|other", "confidence": 0.0-1.0},
  "urgency": "critical|high|medium|low"
}

Output valid JSON only.`,
          },
          { role: LLM_MESSAGE_ROLE.user, content: "{{message}}" },
        ],
      },
      llm_model: {
        model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        parameters: { temperature: 0.7, max_tokens: 500 },
      },
      evaluation: {
        metrics: [
          {
            type: METRIC_TYPE.G_EVAL,
            parameters: {
              task_introduction:
                "You are evaluating a customer support message classifier. The system analyzes incoming customer messages and outputs a JSON object with 'intent' (containing 'primary' category and 'confidence' score) and 'urgency' level. The dataset provides 'expected_intent' and 'expected_urgency' as ground truth labels for each message.",
              evaluation_criteria:
                "Score based on: 1) Intent accuracy - the 'intent.primary' field should match 'expected_intent' from the dataset (question, complaint, request, feedback, or other). Pay attention to sarcasm and indirect phrasing. 2) Urgency accuracy - the 'urgency' field should match 'expected_urgency' (critical for time-sensitive blockers, high for significant issues, medium for moderate concerns, low for minor feedback). 3) Confidence calibration - confidence scores should be lower for ambiguous messages. 4) Valid JSON output format.",
            },
          },
        ],
      },
      optimizer: {
        type: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE,
        parameters: {
          convergence_threshold:
            DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD,
          verbose: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE,
          seed: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED,
        },
      },
    },
  },
  {
    id: "json-output",
    title: "Demo template - JSON Formatter",
    description: "Ensure LLM outputs valid JSON matching a defined schema",
    name: "JSON formatter optimization",
    dataset_id: "",
    dataset_items: JSON_OUTPUT_DATASET_ITEMS,
    studio_config: {
      dataset_name: "Demo - JSON Formatter",
      prompt: {
        messages: [
          {
            role: LLM_MESSAGE_ROLE.system,
            content: `Analyze customer messages and output JSON with this EXACT structure:
{
  "intent": {
    "primary": <"question"|"complaint"|"request"|"feedback"|"other">,
    "confidence": <0.0-1.0>
  },
  "urgency": <"critical"|"high"|"medium"|"low">,
  "sentiment": <"positive"|"neutral"|"negative">,
  "entities": {
    "person_name": <string or null>,
    "product": <string or null>,
    "issue_type": <string or null>
  },
  "action": {
    "type": <"escalate"|"respond"|"investigate"|"close">,
    "team": <"support"|"engineering"|"billing"|"sales">,
    "notes": <string>
  },
  "response": {
    "tone": <"empathetic"|"professional"|"apologetic">,
    "key_points": [<array of strings>]
  }
}

IMPORTANT: Use EXACT field names and enum values shown above (lowercase). Output ONLY valid JSON.`,
          },
          { role: LLM_MESSAGE_ROLE.user, content: "{{message}}" },
        ],
      },
      llm_model: {
        model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        parameters: { temperature: 0.3, max_tokens: 500 },
      },
      evaluation: {
        metrics: [
          {
            type: METRIC_TYPE.JSON_SCHEMA_VALIDATOR,
            parameters: {
              reference_key: "json_schema",
            },
          },
        ],
      },
      optimizer: {
        type: OPTIMIZER_TYPE.GEPA,
        parameters: {
          verbose: DEFAULT_GEPA_OPTIMIZER_CONFIGS.VERBOSE,
          seed: DEFAULT_GEPA_OPTIMIZER_CONFIGS.SEED,
        },
      },
    },
  },
];
