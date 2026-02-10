import { LLM_MESSAGE_ROLE } from "@/types/llm";

export enum INTAKE_EVENT_TYPE {
  text_delta = "text_delta",
  text_done = "text_done",
  input_hint = "input_hint",
  config_updated = "config_updated",
  turn_complete = "turn_complete",
  intake_complete = "intake_complete",
}

export enum INPUT_HINT {
  endpoint_selector = "endpoint_selector",
  text = "text",
  textarea = "textarea",
  yes_no = "yes_no",
  eval_suite_selector = "eval_suite_selector",
  no_endpoints_configured = "no_endpoints_configured",
  none = "none",
}

export type IntakeEvalSuite = {
  enabled: boolean;
  suite_id: string | null;
  suite_name: string | null;
};

export type IntakeConfig = {
  trace_id: string;
  endpoint_id: string;
  expected_behaviors: string[];
  eval_suite: IntakeEvalSuite;
};

export type IntakeMessageMetadata =
  | {
      type: "endpoint_selection";
      endpointName: string;
    }
  | {
      type: "no_endpoints_choice";
      choice: "run_myself" | "setup_endpoint";
    };

export type IntakeMessage = {
  id: string;
  role: LLM_MESSAGE_ROLE.user | LLM_MESSAGE_ROLE.assistant;
  content: string;
  isLoading?: boolean;
  isError?: boolean;
  metadata?: IntakeMessageMetadata;
};

export type IntakeChatState = {
  value: string;
  messages: IntakeMessage[];
  inputHint: INPUT_HINT;
  isReady: boolean;
  config: IntakeConfig | null;
};

export type IntakeStreamEvent = {
  type: INTAKE_EVENT_TYPE;
  content?: string;
  hint?: INPUT_HINT;
  config?: IntakeConfig;
  is_ready?: boolean;
};

export type IntakeStartRequest = {
  trace_info?: {
    prompts?: Array<{ name: string; type: string }>;
    has_endpoints?: boolean;
  };
};

export type IntakeRespondRequest = {
  message: string;
};

export type IntakeStatusResponse = {
  trace_id: string;
  is_ready: boolean;
  config: IntakeConfig | null;
};

// Optimization types
export enum OPTIMIZE_EVENT_TYPE {
  optimization_started = "optimization_started",
  run_status = "run_status",
  run_result = "run_result",
  regression_result = "regression_result",
  optimization_complete = "optimization_complete",
}

export type OptimizationStartedEvent = {
  type: OPTIMIZE_EVENT_TYPE.optimization_started;
  expected_behaviors: string[];
};

export type RunStatus = "running" | "evaluating" | "checking_regressions";

export type RunStatusEvent = {
  type: OPTIMIZE_EVENT_TYPE.run_status;
  label: string;
  iteration: number;
  status: RunStatus;
  trace_id?: string;
};

export type AssertionResult = {
  name: string;
  passed?: boolean;
};

export type RunResultEvent = {
  type: OPTIMIZE_EVENT_TYPE.run_result;
  label: string;
  iteration: number;
  all_passed: boolean;
  assertions: AssertionResult[];
};

export type OptimizationChange = {
  type: string;
  description: string;
};

export type RegressionItem = {
  item_id: string;
  trace_id?: string;
  reason: string;
  failed_assertions: AssertionResult[];
};

export type RegressionResult = {
  run_id?: string;
  iteration: number;
  items_tested: number;
  items_passed: number;
  no_regressions: boolean;
  regressions: RegressionItem[];
  items?: RegressionItem[];
};

export type PromptMessage = {
  role: string;
  content: string;
};

export type PromptVersion = {
  name: string;
  type: "chat" | "text";
  messages?: PromptMessage[];
  template?: string;
};

export type DiffLine = {
  type: "context" | "addition" | "deletion";
  content: string;
  line_original: number | null;
  line_modified: number | null;
};

export type DiffChange = {
  message_index: number | null;
  role: string | null;
  change_type: "modified" | "added" | "removed";
  diff: DiffLine[];
};

export type PromptChange = {
  prompt_name: string;
  original: PromptVersion;
  modified: PromptVersion;
  diff?: DiffChange[];
};

export type ScalarChange = {
  key: string;
  original_value: unknown;
  modified_value: unknown;
};

export type OptimizationCompleteEvent = {
  type: OPTIMIZE_EVENT_TYPE.optimization_complete;
  success: boolean;
  optimization_id?: string;
  iterations?: number;
  changes: OptimizationChange[];
  prompt_changes?: PromptChange[];
  scalar_changes?: ScalarChange[];
  experiment_traces?: Record<string, string>;
  final_assertion_results?: AssertionResult[];
};

export type OptimizeStreamEvent =
  | OptimizationStartedEvent
  | RunStatusEvent
  | RunResultEvent
  | OptimizationCompleteEvent;

export type OptimizationRun = {
  label: string;
  iteration: number;
  status: RunStatus | "checking_regressions" | "completed";
  assertions: AssertionResult[];
  all_passed?: boolean;
  trace_id?: string;
  regression?: RegressionResult;
};
