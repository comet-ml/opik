export type TaskType =
  | "feature-dev"
  | "bug-fix"
  | "refactor"
  | "review"
  | "docs"
  | "devops"
  | "testing"
  | "exploration"
  | "debugging"
  | "config";

export type WorkClassification =
  | "work-repo"
  | "side-project"
  | "personal"
  | "unclear";

export type Outcome =
  | "completed"
  | "abandoned"
  | "blocked"
  | "handed-off"
  | "asked-for-help";

export type RiskFlag =
  | "destructive-command"
  | "force-push"
  | "no-verify"
  | "secret-in-prompt"
  | "customer-data"
  | "pii";

export type ClaudeModel = "claude-sonnet" | "claude-opus" | "claude-haiku";

export interface ClaudeCodeMetadata {
  team: string;
  repository: string;
  task_type: TaskType;
  skill: string;
  subsystem: string;
  work_classification: WorkClassification;
  outcome: Outcome;
  risk_flags: RiskFlag[];
}

export interface ClaudeCodeTrace {
  trace_id: string;
  user_id: string;
  user_name: string;
  model: ClaudeModel;
  input_tokens: number;
  output_tokens: number;
  cost: number;
  duration_ms: number;
  mcp_servers: string[];
  tools_used: string[];
  created_at: string;
  cc: ClaudeCodeMetadata;
}

export interface TeamBudget {
  name: string;
  color: string;
  users: number;
  sessions: number;
  spent: number;
  budget: number;
  projected_eomonth: number;
}

export interface UserSummary {
  user_id: string;
  user_name: string;
  team: string;
  sessions: number;
  spent: number;
}

export interface DailySpendPoint {
  date: string;
  total: number;
  byTeam: Record<string, number>;
}

export interface ModelUsageSlice {
  model: ClaudeModel;
  tokens: number;
  cost: number;
  color: string;
}

export interface TopicBucket {
  task_type: TaskType;
  label: string;
  cost: number;
  sessions: number;
  color: string;
}

export interface SkillCost {
  skill: string;
  cost: number;
}

export interface McpCost {
  server: string;
  cost: number;
}

export interface SideProjectRow {
  user_name: string;
  team: string;
  repository: string;
  sessions: number;
  cost: number;
  first_detected: string;
}

export interface ExpensivePromptRow {
  user_name: string;
  team: string;
  when: string;
  model: ClaudeModel;
  cost: number;
  tokens: number;
  repository: string;
}

export interface HeatmapCell {
  day: number;
  hour: number;
  sessions: number;
}

export interface SankeyFlow {
  label: string;
  /** $ amount this category contributed (LHS) or consumed (RHS). */
  value: number;
  /** Optional grouping for color/legend. */
  group?: "static" | "dynamic" | "thinking" | "tool" | "text" | "mcp" | "skill";
  /** If set, clicking this flow drills into a per-item breakdown by that key. */
  drillKey?:
    | "mcp"
    | "tools"
    | "static"
    | "skills_loaded"
    | "mcp_calls"
    | "skill_calls"
    | "prior_assistant"
    | "tool_results"
    | "user_prompts"
    | "file_attachments"
    | "thinking"
    | "assistant_text";
}

export interface TokenComposition {
  /** Sum of all input flows ($). */
  totalInput: number;
  /** Sum of all output flows ($). */
  totalOutput: number;
  /** Top-level input lanes; click a rollup lane to see its drill-down. */
  input: SankeyFlow[];
  /** Top-level output lanes. */
  output: SankeyFlow[];
  /** Per-MCP-server breakdown — input side (overhead from having MCPs loaded). */
  mcpBreakdown: SankeyFlow[];
  /** Built-in tool breakdown (Edit/Bash/Write/etc) — output side. */
  toolBreakdown: SankeyFlow[];
  /** Per-static-source breakdown — input side. */
  staticBreakdown: SankeyFlow[];
  /** Per-skill body breakdown — input side (skill content loaded into context). */
  skillsLoadedBreakdown: SankeyFlow[];
  /** Per-MCP-server breakdown — output side (cost of MCP tool invocations). */
  mcpCallsBreakdown: SankeyFlow[];
  /** Per-skill breakdown — output side (cost of skill invocations). */
  skillCallsBreakdown: SankeyFlow[];
  /** Prior assistant context — by session-length bucket. */
  priorAssistantBreakdown: SankeyFlow[];
  /** Tool results — by tool that produced the output. */
  toolResultsBreakdown: SankeyFlow[];
  /** User prompts — by prompt-size bucket. */
  userPromptsBreakdown: SankeyFlow[];
  /** File attachments — by attached content type. */
  fileAttachmentsBreakdown: SankeyFlow[];
  /** Thinking — by (model, effort level) combo. */
  thinkingBreakdown: SankeyFlow[];
  /** Assistant text — by output category (code vs prose vs status). */
  assistantTextBreakdown: SankeyFlow[];
}

export type RecommendationSeverity = "high" | "medium" | "low";

export interface Recommendation {
  id: string;
  title: string;
  /** One-sentence rationale, in plain English. */
  body: string;
  /** Estimated monthly $ savings if acted on. */
  estSavingsMonthly: number;
  severity: RecommendationSeverity;
  /** What the primary CTA button says. */
  actionLabel: string;
}

export interface ClaudeCodeData {
  asOf: string;
  windowDays: 7 | 30 | 90;
  org: {
    spendMTD: number;
    budget: number;
    activeUsers: number;
    totalUsers: number;
    activeTeams: number;
    totalSessions: number;
    avgCostPerUser: number;
    flaggedRepoCount: number;
    overBudgetTeamName: string | null;
    overBudgetTeamPct: number;
    offHoursSessions: number;
  };
  teams: TeamBudget[];
  users: UserSummary[];
  dailySpend: DailySpendPoint[];
  modelUsage: ModelUsageSlice[];
  topics: TopicBucket[];
  topicMixByTeam: { team: string; values: Record<TaskType, number> }[];
  topSkills: SkillCost[];
  mcpCosts: McpCost[];
  costliestSkill: SkillCost;
  costliestMcp: McpCost;
  opusUsagePct: number;
  opusUsageCost: number;
  modelTrend: { date: string; sonnet: number; opus: number; haiku: number }[];
  sideProjects: SideProjectRow[];
  expensivePrompts: ExpensivePromptRow[];
  heatmap: HeatmapCell[];
  recentTraces: ClaudeCodeTrace[];
  /** Aggregate token-spend composition, summed across all users in the window. */
  tokenComposition: TokenComposition;
  /** Suggested next actions, prioritized by est savings. */
  recommendations: Recommendation[];
}
