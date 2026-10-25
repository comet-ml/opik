import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { EVALUATORS_RULE_SCOPE, PythonCodeObject } from "@/types/automations";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMMessage,
} from "@/types/llm";
import { DECISION_MODELS } from "@/constants/decisionModels";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import {
  DEFAULT_PYTHON_CODE_SPAN_DATA,
  DEFAULT_PYTHON_CODE_THREAD_DATA,
  DEFAULT_PYTHON_CODE_TRACE_DATA,
} from "@/constants/llm";

export const DEFAULT_SAMPLING_RATE = 1;

export const RULE_LAST_PICKED_MODEL = "online-evaluation-last-picked-model";

export const RULE_DEFAULT_MODEL_BY_PROVIDER: Partial<
  Record<PROVIDER_TYPE, PROVIDER_MODEL_TYPE>
> = {
  [PROVIDER_TYPE.OPIK_FREE]: PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
  [PROVIDER_TYPE.OPEN_AI]: PROVIDER_MODEL_TYPE.GPT_5_5,
  [PROVIDER_TYPE.ANTHROPIC]: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
  [PROVIDER_TYPE.GEMINI]: PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE,
  [PROVIDER_TYPE.VERTEX_AI]:
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
  [PROVIDER_TYPE.OPEN_ROUTER]: PROVIDER_MODEL_TYPE.OPENAI_GPT_5_5,
};

const DEFAULT_LLM_CONFIG = {
  temperature: 0.0,
  seed: null,
  custom_parameters: null,
};

const EMPTY_JUDGE_DATA = {
  model: "",
  config: DEFAULT_LLM_CONFIG,
  template: LLM_JUDGE.custom,
  messages: [] as LLMMessage[],
  variables: {},
  schema: [
    {
      name: "",
      description: "",
      type: LLM_SCHEMA_TYPE.BOOLEAN,
      unsaved: false,
    },
  ],
  maxCostUsd: null,
};

const getTaskCompletionData = (
  content: string,
  variables: Record<string, string>,
) => ({
  ...EMPTY_JUDGE_DATA,
  messages: [{ id: "rule-prompt", role: LLM_MESSAGE_ROLE.user, content }],
  variables,
});

export const TASK_COMPLETION_DATA = {
  [EVALUATORS_RULE_SCOPE.trace]: getTaskCompletionData(
    [
      "You are an impartial judge. Decide if the agent completed the task the user asked for.",
      "",
      "## How to judge",
      "- Find the user's request in the trace input.",
      "- Check the steps the agent took and the final output, not only the final answer.",
      "",
      "## Score",
      "- true: the final output achieves the user's goal.",
      "- false: the task is incomplete, wrong or not attempted, or the agent claims success without doing the work.",
      "",
      "Explain the score in one sentence.",
      "",
      "## Trace",
      "{{trace}}",
    ].join("\n"),
    { trace: "trace" },
  ),
  [EVALUATORS_RULE_SCOPE.thread]: getTaskCompletionData(
    [
      "You are an impartial judge. Decide if the agent completed what the user asked for in this conversation.",
      "",
      "## How to judge",
      "- Find what the user wanted, including follow-up requests.",
      "- Check the agent's replies against each request.",
      "",
      "## Score",
      "- true: the agent achieved the user's goal by the end of the conversation.",
      "- false: a request is incomplete, answered wrongly or ignored.",
      "",
      "Explain the score in one sentence that points to a specific message.",
      "",
      "## Conversation",
      "{{context}}",
    ].join("\n"),
    { context: "" },
  ),
  [EVALUATORS_RULE_SCOPE.span]: getTaskCompletionData(
    [
      "You are an impartial judge. Decide if this step completed the task it was given.",
      "",
      "## How to judge",
      "- Find the task in the span input.",
      "- Check if the span output does that task.",
      "",
      "## Score",
      "- true: the output completes the task correctly.",
      "- false: the output is incomplete, wrong, an error, or does not match the input.",
      "",
      "Explain the score in one sentence.",
      "",
      "## Span",
      "{{span}}",
    ].join("\n"),
    { span: "span" },
  ),
};

export const DEFAULT_LLM_AS_JUDGE_DATA = {
  [EVALUATORS_RULE_SCOPE.trace]: EMPTY_JUDGE_DATA,
  [EVALUATORS_RULE_SCOPE.thread]: EMPTY_JUDGE_DATA,
  [EVALUATORS_RULE_SCOPE.span]: EMPTY_JUDGE_DATA,
};

const DECISION_MODEL_DEFAULT_DATA = {
  ...EMPTY_JUDGE_DATA,
  model: DECISION_MODELS[0].value,
};

export const DEFAULT_DECISION_MODEL_DATA = {
  [EVALUATORS_RULE_SCOPE.trace]: DECISION_MODEL_DEFAULT_DATA,
  [EVALUATORS_RULE_SCOPE.thread]: DECISION_MODEL_DEFAULT_DATA,
  [EVALUATORS_RULE_SCOPE.span]: DECISION_MODEL_DEFAULT_DATA,
};

export const DEFAULT_PYTHON_CODE_DATA: Record<
  EVALUATORS_RULE_SCOPE,
  PythonCodeObject
> = {
  [EVALUATORS_RULE_SCOPE.trace]: DEFAULT_PYTHON_CODE_TRACE_DATA,
  [EVALUATORS_RULE_SCOPE.thread]: DEFAULT_PYTHON_CODE_THREAD_DATA,
  [EVALUATORS_RULE_SCOPE.span]: DEFAULT_PYTHON_CODE_SPAN_DATA,
};

export const TRACE_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  { id: "id", label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "input", label: "Input", type: COLUMN_TYPE.dictionary },
  { id: "output", label: "Output", type: COLUMN_TYPE.dictionary },
  { id: "duration", label: "Duration (s)", type: COLUMN_TYPE.duration },
  { id: COLUMN_METADATA_ID, label: "Metadata", type: COLUMN_TYPE.dictionary },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "thread_id", label: "Thread ID", type: COLUMN_TYPE.string },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

export const THREAD_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  { id: "status", label: "Status", type: COLUMN_TYPE.string },
  {
    id: "number_of_messages",
    label: "Message count",
    type: COLUMN_TYPE.number,
  },
  { id: "created_at", label: "Created at", type: COLUMN_TYPE.time },
  { id: "last_updated_at", label: "Last updated at", type: COLUMN_TYPE.time },
  { id: "duration", label: "Duration (s)", type: COLUMN_TYPE.duration },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

export const SPAN_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  { id: "id", label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "input", label: "Input", type: COLUMN_TYPE.string },
  { id: "output", label: "Output", type: COLUMN_TYPE.string },
  { id: "duration", label: "Duration (s)", type: COLUMN_TYPE.duration },
  { id: COLUMN_METADATA_ID, label: "Metadata", type: COLUMN_TYPE.dictionary },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "type", label: "Type", type: COLUMN_TYPE.category },
  { id: "model", label: "Model", type: COLUMN_TYPE.string },
  { id: "provider", label: "Provider", type: COLUMN_TYPE.string },
  { id: "usage.total_tokens", label: "Total tokens", type: COLUMN_TYPE.number },
  {
    id: "usage.prompt_tokens",
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.completion_tokens",
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
  },
  {
    id: "total_estimated_cost",
    label: "Estimated cost",
    type: COLUMN_TYPE.cost,
  },
  { id: "error_info", label: "Errors", type: COLUMN_TYPE.errors },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

export const FILTER_COLUMNS_BY_SCOPE: Record<
  EVALUATORS_RULE_SCOPE,
  ColumnData<TRACE_DATA_TYPE>[]
> = {
  [EVALUATORS_RULE_SCOPE.trace]: TRACE_FILTER_COLUMNS,
  [EVALUATORS_RULE_SCOPE.thread]: THREAD_FILTER_COLUMNS,
  [EVALUATORS_RULE_SCOPE.span]: SPAN_FILTER_COLUMNS,
};

export const SCOPE_ENTITY_LABEL: Record<EVALUATORS_RULE_SCOPE, string> = {
  [EVALUATORS_RULE_SCOPE.trace]: "traces",
  [EVALUATORS_RULE_SCOPE.thread]: "threads",
  [EVALUATORS_RULE_SCOPE.span]: "spans",
};
