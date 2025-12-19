import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  Optimization,
} from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";

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
  { value: OPTIMIZER_TYPE.GEPA, label: "GEPA optimizer" },
  {
    value: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE,
    label: "Hierarchical Reflective",
  },
];

export const OPTIMIZATION_METRIC_OPTIONS = [
  { value: METRIC_TYPE.EQUALS, label: "Equals" },
  { value: METRIC_TYPE.JSON_SCHEMA_VALIDATOR, label: "JSON Schema Validator" },
  { value: METRIC_TYPE.G_EVAL, label: "Custom (G-Eval)" },
  { value: METRIC_TYPE.LEVENSHTEIN, label: "Levenshtein" },
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
    question: "How do I install the Opik Python SDK?",
    expected_behavior: "answer",
    answer:
      "You can install the Opik Python SDK using pip: `pip install opik`. After installation, configure it with `opik.configure()` to connect to your Opik backend.",
  },
  {
    question: "What is the @track decorator used for in Opik?",
    expected_behavior: "answer",
    answer:
      "The @track decorator automatically logs traces for your functions. It captures inputs, outputs, and timing information, making it easy to monitor LLM calls and debug your AI applications.",
  },
  {
    question: "How can I evaluate my LLM outputs using Opik?",
    expected_behavior: "answer",
    answer:
      "Opik provides the `evaluate()` function to run evaluations over datasets. You can use built-in metrics like Hallucination, AnswerRelevance, or create custom metrics to assess your LLM outputs.",
  },
  {
    question: "What metrics does Opik support for evaluation?",
    expected_behavior: "answer",
    answer:
      "Opik supports various metrics including Hallucination detection, AnswerRelevance, ContextPrecision, ContextRecall, Contains, Equals, LevenshteinRatio, and custom LLM-as-judge metrics via GEval.",
  },
  {
    question: "How do I create a dataset in Opik?",
    expected_behavior: "answer",
    answer:
      "You can create a dataset using the Opik client: `client = opik.Opik()` then `dataset = client.create_dataset(name='my_dataset')`. Add items with `dataset.insert(items)` where items is a list of dictionaries.",
  },
  {
    question: "Can Opik integrate with LangChain?",
    expected_behavior: "answer",
    answer:
      "Yes, Opik integrates with LangChain. You can use `OpikTracer` as a callback to automatically trace all LangChain operations. Install with `pip install opik[langchain]`.",
  },
  {
    question: "What is Opik's Optimization Studio?",
    expected_behavior: "answer",
    answer:
      "Optimization Studio is Opik's feature for automated prompt optimization. It uses algorithms like MIPRO and evolutionary optimization to improve your prompts based on evaluation metrics and datasets.",
  },
  {
    question: "How do I view traces in the Opik UI?",
    expected_behavior: "answer",
    answer:
      "After logging traces with the SDK, open the Opik UI and navigate to the Traces section. You'll see all logged traces with their inputs, outputs, timing, and any feedback scores.",
  },
  {
    question: "Does Opik support OpenAI integration?",
    expected_behavior: "answer",
    answer:
      "Yes, Opik integrates with OpenAI. You can use `track_openai()` to automatically trace all OpenAI API calls, capturing prompts, completions, token usage, and costs.",
  },
  {
    question: "How do I add feedback scores to traces in Opik?",
    expected_behavior: "answer",
    answer:
      "You can add feedback scores programmatically using `client.log_traces_feedback_scores()` or through the Opik UI by clicking on a trace and adding a score manually.",
  },
  {
    question: "What's the weather like in San Francisco today?",
    expected_behavior: "decline",
    answer:
      "I'm an Opik assistant, specialized in helping with Opik SDK, tracing, evaluation, and prompt optimization. I can't provide weather information. Is there anything about Opik I can help you with?",
  },
  {
    question: "Can you write me a poem about the ocean?",
    expected_behavior: "decline",
    answer:
      "I appreciate the creative request, but I'm focused on helping with Opik-related questions about LLM tracing, evaluation, and optimization. Would you like to know how Opik can help with your AI projects instead?",
  },
  {
    question: "What's the best recipe for chocolate cake?",
    expected_behavior: "decline",
    answer:
      "I'm an Opik assistant and can't help with recipes. I specialize in Opik's features for LLM observability and evaluation. Is there something about Opik I can assist you with?",
  },
  {
    question: "Who won the World Cup in 2022?",
    expected_behavior: "decline",
    answer:
      "I'm not able to answer sports questions. I'm here to help with Opik - the LLM observability and evaluation platform. Do you have any questions about tracing, metrics, or prompt optimization?",
  },
  {
    question: "Can you help me debug my React application?",
    expected_behavior: "decline",
    answer:
      "I specialize in Opik, not general web development. However, if you're building an AI-powered React app and want to trace your LLM calls with Opik, I'd be happy to help with that!",
  },
  {
    question: "What's the capital of France?",
    expected_behavior: "decline",
    answer:
      "I'm focused on Opik-related topics and can't answer general knowledge questions. If you're curious about how to use Opik for your projects, I'm here to help!",
  },
  {
    question: "Tell me a joke",
    expected_behavior: "decline",
    answer:
      "While I appreciate the request for humor, I'm an Opik-focused assistant. I'm best at helping you with LLM tracing, evaluation metrics, and prompt optimization. Any Opik questions I can help with?",
  },
  {
    question: "How do I invest in stocks?",
    expected_behavior: "decline",
    answer:
      "Financial advice isn't my area of expertise. I'm here to help with Opik - for tracing LLM applications, running evaluations, and optimizing prompts. What would you like to know about Opik?",
  },
  {
    question: "What's the meaning of life?",
    expected_behavior: "decline",
    answer:
      "That's a profound philosophical question beyond my scope. I'm specialized in Opik for LLM observability. If you're building AI applications and need help with tracing or evaluation, I'm your assistant!",
  },
  {
    question: "Can you summarize the news today?",
    expected_behavior: "decline",
    answer:
      "I don't have access to current news and focus specifically on Opik-related assistance. I can help you with LLM tracing, evaluation, datasets, and prompt optimization. What Opik topic interests you?",
  },
];

// JSON output dataset items - tasks to generate structured JSON data
const JSON_OUTPUT_DATASET_ITEMS: DemoDatasetItem[] = [
  {
    input_description: "Create a person object with name 'John Doe' and age 30",
    json_schema: {
      type: "object",
      properties: {
        name: { type: "string" },
        age: { type: "integer" },
      },
      required: ["name", "age"],
    },
  },
  {
    input_description:
      "Generate a product with name 'Laptop', price 999.99, and in_stock true",
    json_schema: {
      type: "object",
      properties: {
        name: { type: "string" },
        price: { type: "number" },
        in_stock: { type: "boolean" },
      },
      required: ["name", "price", "in_stock"],
    },
  },
  {
    input_description:
      "Create an address with street '123 Main St', city 'New York', and zip '10001'",
    json_schema: {
      type: "object",
      properties: {
        street: { type: "string" },
        city: { type: "string" },
        zip: { type: "string" },
      },
      required: ["street", "city", "zip"],
    },
  },
  {
    input_description: "Generate an array of 3 colors: red, green, and blue",
    json_schema: {
      type: "array",
      items: { type: "string" },
      minItems: 3,
      maxItems: 3,
    },
  },
  {
    input_description:
      "Create a book object with title 'The Great Gatsby', author 'F. Scott Fitzgerald', year 1925, and genres array containing 'fiction' and 'classic'",
    json_schema: {
      type: "object",
      properties: {
        title: { type: "string" },
        author: { type: "string" },
        year: { type: "integer" },
        genres: {
          type: "array",
          items: { type: "string" },
        },
      },
      required: ["title", "author", "year", "genres"],
    },
  },
  {
    input_description:
      "Generate a user profile with username 'alice123', email 'alice@example.com', and active status true",
    json_schema: {
      type: "object",
      properties: {
        username: { type: "string" },
        email: { type: "string", format: "email" },
        active: { type: "boolean" },
      },
      required: ["username", "email", "active"],
    },
  },
  {
    input_description:
      "Create an event with name 'Tech Conference', date '2024-06-15', and attendees count of 500",
    json_schema: {
      type: "object",
      properties: {
        name: { type: "string" },
        date: { type: "string" },
        attendees: { type: "integer" },
      },
      required: ["name", "date", "attendees"],
    },
  },
  {
    input_description:
      "Generate a simple API response with status 'success', code 200, and message 'Data retrieved successfully'",
    json_schema: {
      type: "object",
      properties: {
        status: { type: "string" },
        code: { type: "integer" },
        message: { type: "string" },
      },
      required: ["status", "code", "message"],
    },
  },
  {
    input_description:
      "Create a coordinate point with x value 10.5 and y value -20.3",
    json_schema: {
      type: "object",
      properties: {
        x: { type: "number" },
        y: { type: "number" },
      },
      required: ["x", "y"],
    },
  },
  {
    input_description:
      "Generate a task object with id 1, title 'Complete report', completed false, and priority 'high'",
    json_schema: {
      type: "object",
      properties: {
        id: { type: "integer" },
        title: { type: "string" },
        completed: { type: "boolean" },
        priority: { type: "string", enum: ["low", "medium", "high"] },
      },
      required: ["id", "title", "completed", "priority"],
    },
  },
];

export const OPTIMIZATION_DEMO_TEMPLATES: OptimizationTemplate[] = [
  {
    id: "opik-chatbot",
    title: "Demo - Opik Chatbot",
    description:
      "Train a chatbot to answer Opik questions and decline off-topic ones",
    dataset_id: "",
    dataset_items: CHATBOT_DATASET_ITEMS,
    studio_config: {
      dataset_name: "Demo - Opik Chatbot",
      prompt: {
        messages: [
          {
            role: LLM_MESSAGE_ROLE.system,
            content: "You are an Opik assistant.",
          },
          { role: LLM_MESSAGE_ROLE.user, content: "{{question}}" },
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
                "Evaluate if the chatbot correctly handles Opik-related vs off-topic questions. The dataset contains a field 'expected_behavior' which is either 'answer' (for Opik questions) or 'decline' (for off-topic questions).",
              evaluation_criteria:
                "1. For Opik-related questions (expected_behavior='answer'): Response should be helpful, accurate, and informative about Opik features. 2. For off-topic questions (expected_behavior='decline'): Response should politely decline and redirect the user to Opik-related topics. 3. The chatbot should never make up information about non-Opik topics.",
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
    title: "Demo - Ensure JSON output",
    description:
      "Generate valid JSON without markdown formatting based on descriptions",
    dataset_id: "",
    dataset_items: JSON_OUTPUT_DATASET_ITEMS,
    studio_config: {
      dataset_name: "Demo - JSON Output",
      prompt: {
        messages: [
          {
            role: LLM_MESSAGE_ROLE.system,
            content:
              "You are a JSON generator. Generate valid JSON based on the user's description.",
          },
          { role: LLM_MESSAGE_ROLE.user, content: "{{input_description}}" },
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
