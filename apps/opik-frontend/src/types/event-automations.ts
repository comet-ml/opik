// Event-triggered automations (`/v1/private/automations`), their workflows,
// recommendations and loop. Distinct from the evaluator rules in `automations.ts`,
// which share the word. JSON is snake_case, as on the wire.

export type AutomationRecipe = {
  id: string;
  version: number;
};

export type ScoreCondition = {
  score_name: string;
  operator: string;
  value: number;
};

export type ScoreConditions = {
  groups: { conditions: ScoreCondition[] }[];
};

export type StepOutputCondition = {
  step_output: string;
  operator: string;
  value: number;
};

export type StepConditions = {
  groups: { conditions: StepOutputCondition[] }[];
};

export type AutomationTrigger = {
  event: string;
  scope?: "trace" | "thread" | null;
  conditions?: ScoreConditions | null;
  per_day?: number | null;
  prompt_id?: string | null;
  prompt_name?: string | null;
  test_suite_id?: string | null;
  test_suite_name?: string | null;
  annotation_queue_id?: string | null;
  annotation_queue_name?: string | null;
  n?: number | null;
  repository?: string | null;
  branch?: string | null;
};

export type AutomationStep = {
  id: string;
  type: string;
  annotation_queue_id?: string | null;
  annotation_queue_name?: string | null;
  test_suite_id?: string | null;
  test_suite_name?: string | null;
  dataset_id?: string | null;
  dataset_name?: string | null;
  model?: string | null;
  prompt_id?: string | null;
  prompt_name?: string | null;
  repository?: string | null;
  context?: string | null;
  url?: string | null;
  kind?: "webhook" | "slack" | null;
  message?: string | null;
  metric?: string | null;
  optimizer?: string | null;
  score_name?: string | null;
  threshold?: number | null;
  only_if?: StepConditions | null;
};

export type AutomationLimits = {
  max_items_per_day?: number | null;
  max_runs_per_day?: number | null;
  max_cost_usd_per_day?: number | null;
};

export type AutomationStats = {
  window_days: number;
  fired: number;
  matched: number;
  sampled: number;
  steps_done: number;
  steps_skipped: number;
  steps_failed: number;
  items_written: number;
  cost_usd: number;
};

export type AutomationPausedReason = {
  limit: string;
  until: string;
};

export type AutomationCreatedVia = "mcp" | "ui" | "api";

export type Automation = {
  id: string;
  project_id: string;
  project_name?: string | null;
  name: string;
  enabled: boolean;
  recipe?: AutomationRecipe | null;
  triggers: AutomationTrigger[];
  steps: AutomationStep[];
  limits?: AutomationLimits | null;
  created_via?: AutomationCreatedVia | null;
  stats?: AutomationStats | null;
  paused_reason?: AutomationPausedReason | null;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
};

export type AutomationUpdate = {
  name?: string;
  enabled?: boolean;
  triggers?: AutomationTrigger[];
  steps?: AutomationStep[];
  limits?: AutomationLimits;
};

export type AutomationsListResponse = {
  content: Automation[];
  page: number;
  size: number;
  total: number;
};

export type AutomationRunKind = "rollup" | "single";
export type AutomationRunStatus = "pending" | "done" | "partial" | "failed";
export type AutomationRunStepStatus = "done" | "skipped" | "failed";

export type AutomationRunTrigger = {
  event: string;
  scope?: "trace" | "thread" | null;
  fired: number;
  matched: number;
  sampled: number;
  item_id?: string | null;
};

export type AutomationRunStep = {
  id: string;
  type: string;
  done: number;
  skipped: number;
  failed: number;
  status?: AutomationRunStepStatus | null;
  output?: unknown;
  reason?: string | null;
  last_error?: string | null;
};

export type AutomationRun = {
  id: string;
  automation_id: string;
  project_id: string;
  kind: AutomationRunKind;
  status: AutomationRunStatus;
  trigger?: AutomationRunTrigger | null;
  steps: AutomationRunStep[];
  cost_usd?: number | null;
  parent_run_id?: string | null;
  child_run_ids?: string[] | null;
  item_id?: string | null;
  trigger_payload?: unknown;
  outputs?: Record<string, unknown> | null;
  resume_step?: number | null;
  waiting_on?: string | null;
  depth: number;
  last_error?: string | null;
  window_start?: string | null;
  window_end?: string | null;
  started_at?: string | null;
  finished_at?: string | null;
};

export type AutomationRunsListResponse = {
  content: AutomationRun[];
  page: number;
  size: number;
  total: number;
};

export type AutomationEstimate = {
  window_days: number;
  fired: number;
  matched: number;
  items: number;
  cost_usd: number;
};

export type WorkflowState = "off" | "partly_on" | "on";

export type WorkflowOverrides = {
  score_name?: string | null;
  threshold?: number | null;
  test_suite_name?: string | null;
  annotation_queue_name?: string | null;
  per_day?: number | null;
  max_items_per_day?: number | null;
  prompt_name?: string | null;
  model?: string | null;
  repository?: string | null;
  notify_url?: string | null;
  metric?: string | null;
  optimizer?: string | null;
  n?: number | null;
  min_gain?: number | null;
};

export type Workflow = {
  recipe: string;
  version: number;
  name: string;
  description?: string | null;
  state: WorkflowState;
  prerequisites_met: boolean;
  blocked_reason?: string | null;
  automations: Automation[];
  estimate?: AutomationEstimate | null;
  overrides?: WorkflowOverrides | null;
  available_score_names?: string[] | null;
};

export type WorkflowEnableRequest = {
  recipe: string;
  project_id: string;
  overrides?: WorkflowOverrides;
  created_via?: AutomationCreatedVia;
};

export type WorkflowEnabled = {
  created: Automation[];
  would_create: string[];
  estimate?: AutomationEstimate | null;
  setup?: { github_action?: string | null } | null;
  warnings: string[];
};

export type RecommendationKind = "setup" | "tune" | "review" | "remove";

export type RecommendationApply = {
  operation: string;
  data: Record<string, unknown>;
};

export type Recommendation = {
  id: string;
  kind: RecommendationKind;
  workflow?: string | null;
  automation_id?: string | null;
  reason: string;
  evidence?: Record<string, unknown> | null;
  blocked_until?: string | null;
  apply?: RecommendationApply | null;
};

export type LoopNode = {
  id: string;
  kind: string;
  name: string;
  object_id?: string | null;
};

export type LoopEdge = {
  automation_id: string;
  name: string;
  recipe?: string | null;
  from: string;
  to: string[];
  produced: Record<string, unknown>;
};

export type Loop = {
  project_id: string;
  window_days: number;
  nodes: LoopNode[];
  edges: LoopEdge[];
};
