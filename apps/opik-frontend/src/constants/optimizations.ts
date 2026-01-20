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

export const DEFAULT_CODE_METRIC_CONFIGS = {
  CODE: `def evaluation_metric(dataset_item, llm_output):
    # Add your evaluation logic here
    # dataset_item: dict with input data and expected values
    # llm_output: string with the LLM response
    
    return ScoreResult(
        name="my_metric",
        value=1.0,  # Score between 0.0 and 1.0
        reason="Evaluation reason"
    )`,
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
  {
    value: METRIC_TYPE.CODE,
    label: "Code",
    description: "Runs custom Python code to evaluate outputs.",
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

// ICP Classification from structured lead data
// SECRET RULES (must be deduced from examples):
//
// "ideal" = BOTH conditions:
//   - industry IN ["saas", "fintech"]
//   - AND employees >= 50
//
// "poor_fit" = ANY of these:
//   - industry IN ["education", "nonprofit"]
//   - OR tools includes "spreadsheets"
//
// "potential" = Everything else
//
// TRICKY ASPECTS:
// - Industry matters but only specific ones (not "tech" broadly)
// - Employee count threshold is 50 (not obvious round numbers like 100)
// - Tool usage is a disqualifier (spreadsheets = not tech-mature)
// - Role and budget are RED HERRINGS - they don't affect classification!
//
const CHATBOT_DATASET_ITEMS: DemoDatasetItem[] = [
  // IDEAL - saas/fintech AND employees >= 50
  {
    industry: "saas",
    employees: "120",
    role: "engineer",
    tools: "jira, slack",
    budget: "$1k-10k",
    expected_icp: "ideal",
  },
  {
    industry: "fintech",
    employees: "50",
    role: "intern",
    tools: "github, notion",
    budget: "$0-1k",
    expected_icp: "ideal",
  },
  {
    industry: "saas",
    employees: "500",
    role: "ceo",
    tools: "salesforce",
    budget: "$10k+",
    expected_icp: "ideal",
  },
  {
    industry: "fintech",
    employees: "85",
    role: "manager",
    tools: "linear, figma",
    budget: "undisclosed",
    expected_icp: "ideal",
  },
  {
    industry: "saas",
    employees: "200",
    role: "director",
    tools: "asana, slack",
    budget: "$1k-10k",
    expected_icp: "ideal",
  },

  // POOR_FIT - education/nonprofit OR uses spreadsheets
  {
    industry: "education",
    employees: "500",
    role: "director",
    tools: "canvas, zoom",
    budget: "$10k+",
    expected_icp: "poor_fit",
  },
  {
    industry: "nonprofit",
    employees: "100",
    role: "vp",
    tools: "monday, slack",
    budget: "$1k-10k",
    expected_icp: "poor_fit",
  },
  {
    industry: "retail",
    employees: "1000",
    role: "cto",
    tools: "spreadsheets, email",
    budget: "$10k+",
    expected_icp: "poor_fit",
  },
  {
    industry: "manufacturing",
    employees: "200",
    role: "manager",
    tools: "spreadsheets",
    budget: "$1k-10k",
    expected_icp: "poor_fit",
  },
  {
    industry: "education",
    employees: "50",
    role: "engineer",
    tools: "github",
    budget: "$0-1k",
    expected_icp: "poor_fit",
  },

  // POTENTIAL - not saas/fintech with 50+, not education/nonprofit, no spreadsheets
  {
    industry: "healthcare",
    employees: "300",
    role: "director",
    tools: "jira, confluence",
    budget: "$10k+",
    expected_icp: "potential",
  },
  {
    industry: "retail",
    employees: "150",
    role: "vp",
    tools: "shopify, slack",
    budget: "$1k-10k",
    expected_icp: "potential",
  },
  {
    industry: "saas",
    employees: "30",
    role: "ceo",
    tools: "notion, linear",
    budget: "$10k+",
    expected_icp: "potential",
  },
  {
    industry: "fintech",
    employees: "25",
    role: "engineer",
    tools: "github, jira",
    budget: "$1k-10k",
    expected_icp: "potential",
  },
  {
    industry: "ecommerce",
    employees: "80",
    role: "manager",
    tools: "hubspot, slack",
    budget: "undisclosed",
    expected_icp: "potential",
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

// JSON output dataset - Ticket routing with JSON schema validation
const JSON_OUTPUT_DATASET_ITEMS: DemoDatasetItem[] = [
  // BILLING - Payment/money related
  {
    message: "I was charged twice for my subscription this month.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "billing",
  },
  {
    message: "Can I get a refund for the unused portion of my plan?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "billing",
  },
  {
    message: "Please send me the invoice for September.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "billing",
  },
  {
    message: "Why did my payment fail when I have sufficient funds?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "billing",
  },

  // TECHNICAL - Bug/error related
  {
    message: "The app crashes every time I try to upload a file.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "technical",
  },
  {
    message: "I keep getting a 404 error when accessing my dashboard.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "technical",
  },
  {
    message: "The export feature is broken and returns empty files.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "technical",
  },
  {
    message: "Login fails even with correct credentials.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "technical",
  },

  // GENERAL - Everything else
  {
    message: "How do I add new team members to my workspace?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "general",
  },
  {
    message: "What's the difference between Pro and Enterprise plans?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "general",
  },
  {
    message: "Can you recommend the best settings for my use case?",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "general",
  },
  {
    message: "I'd like to schedule a demo for my team.",
    json_schema: EXPECTED_JSON_SCHEMA,
    expected_category: "general",
  },
];

export const OPTIMIZATION_DEMO_TEMPLATES: OptimizationTemplate[] = [
  {
    id: "icp-classification",
    title: "Demo template - ICP Classifier",
    description: "Classify leads by Ideal Customer Profile fit",
    name: "ICP classification optimization",
    dataset_id: "",
    dataset_items: CHATBOT_DATASET_ITEMS,
    studio_config: {
      dataset_name: "Demo - ICP Classifier",
      prompt: {
        messages: [
          {
            role: LLM_MESSAGE_ROLE.system,
            content: `Classify the lead by ICP (Ideal Customer Profile) fit.

Output JSON with:
{
  "icp": "ideal|potential|poor_fit"
}

Output valid JSON only.`,
          },
          {
            role: LLM_MESSAGE_ROLE.user,
            content: `Industry: {{industry}}
Employees: {{employees}}
Role: {{role}}
Tools: {{tools}}
Budget: {{budget}}`,
          },
        ],
      },
      llm_model: {
        model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
        parameters: { temperature: 0.7, max_tokens: 100 },
      },
      evaluation: {
        metrics: [
          {
            type: METRIC_TYPE.CODE,
            parameters: {
              code: `def evaluation_metric(dataset_item, llm_output):
    try:
        output = llm_output.strip()
        if output.startswith("${"```"}"):
            output = output.split("${"```"}")[1]
            if output.startswith("json"):
                output = output[4:]
        result = json.loads(output)

        predicted = result.get("icp", "").lower()
        expected = dataset_item.get("expected_icp", "").lower()

        if predicted == expected:
            return ScoreResult(
                name="icp_classification",
                value=1.0,
                reason=f"Correct: {predicted}"
            )
        else:
            return ScoreResult(
                name="icp_classification",
                value=0.0,
                reason=f"Wrong: expected '{expected}', got '{predicted}'"
            )

    except Exception as e:
        return ScoreResult(
            name="icp_classification",
            value=0.0,
            reason=f"Parse error: {str(e)}"
        )`,
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
