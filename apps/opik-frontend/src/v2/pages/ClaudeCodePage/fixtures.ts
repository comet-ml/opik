import {
  ClaudeCodeData,
  ClaudeCodeTrace,
  DailySpendPoint,
  ExpensivePromptRow,
  HeatmapCell,
  ModelUsageSlice,
  Recommendation,
  SankeyFlow,
  SideProjectRow,
  SkillCost,
  TaskType,
  TeamBudget,
  TokenComposition,
  TopicBucket,
  UserSummary,
} from "./types";

const COLORS = {
  platformEng: "hsl(217 91% 60%)",
  frontend: "hsl(160 84% 39%)",
  dataScience: "hsl(280 65% 60%)",
  devops: "hsl(25 95% 53%)",
  research: "hsl(173 80% 40%)",
  mobile: "hsl(330 81% 60%)",
  taskColors: {
    "feature-dev": "hsl(217 91% 60%)",
    "bug-fix": "hsl(0 84% 60%)",
    review: "hsl(160 84% 39%)",
    refactor: "hsl(195 80% 50%)",
    docs: "hsl(280 65% 60%)",
    devops: "hsl(43 96% 56%)",
    testing: "hsl(25 95% 53%)",
    exploration: "hsl(330 81% 60%)",
    debugging: "hsl(0 70% 50%)",
    config: "hsl(220 14% 60%)",
  } as Record<TaskType, string>,
  sonnet: "hsl(217 91% 60%)",
  opus: "hsl(280 65% 60%)",
  haiku: "hsl(160 84% 39%)",
};

function seeded(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 0xffffffff;
  };
}

const TEAMS: TeamBudget[] = [
  {
    name: "Platform Engineering",
    color: COLORS.platformEng,
    users: 8,
    sessions: 2891,
    spent: 2340,
    budget: 2560,
    projected_eomonth: 2580,
  },
  {
    name: "Frontend",
    color: COLORS.frontend,
    users: 6,
    sessions: 1923,
    spent: 1820,
    budget: 2400,
    projected_eomonth: 2187,
  },
  {
    name: "Data Science",
    color: COLORS.dataScience,
    users: 5,
    sessions: 1412,
    spent: 1240,
    budget: 2000,
    projected_eomonth: 1490,
  },
  {
    name: "DevOps / SRE",
    color: COLORS.devops,
    users: 4,
    sessions: 891,
    spent: 890,
    budget: 1600,
    projected_eomonth: 1069,
  },
  {
    name: "Research",
    color: COLORS.research,
    users: 3,
    sessions: 621,
    spent: 620,
    budget: 1200,
    projected_eomonth: 745,
  },
  {
    name: "Mobile",
    color: COLORS.mobile,
    users: 4,
    sessions: 503,
    spent: 480,
    budget: 1200,
    projected_eomonth: 576,
  },
];

const USER_NAMES = [
  "James Smith",
  "Sarah Chen",
  "Alan Chen",
  "Maya Patel",
  "Diego Romero",
  "Priya Krishnan",
  "Liam O'Connor",
  "Hana Tanaka",
  "Marcus Johnson",
  "Elena Volkov",
  "Noah Williams",
  "Aisha Mensah",
  "Tomás Silva",
  "Yuki Sato",
  "Ravi Sharma",
  "Olivia Brown",
  "Khalil Hassan",
  "Sofia Rossi",
  "Daniel Kim",
  "Isabella Garcia",
  "Ethan Park",
  "Zoe Lindqvist",
  "Mateo Fernández",
  "Ananya Rao",
  "Lucas Müller",
  "Emma Davis",
  "Hiro Yamamoto",
  "Chloe Bernard",
  "Felix Andersson",
  "Mia Nakamura",
];

function buildUsers(): UserSummary[] {
  const rand = seeded(42);
  const users: UserSummary[] = [];
  let idx = 0;
  for (const team of TEAMS) {
    const teamUserCount = team.users;
    const perUserCost = team.spent / teamUserCount;
    const perUserSessions = Math.floor(team.sessions / teamUserCount);
    for (let i = 0; i < teamUserCount; i++) {
      const name = USER_NAMES[idx % USER_NAMES.length];
      idx++;
      const jitter = 0.6 + rand() * 0.8;
      users.push({
        user_id: name.toLowerCase().replace(/[^a-z]+/g, "."),
        user_name: name,
        team: team.name,
        sessions: Math.max(1, Math.floor(perUserSessions * jitter)),
        spent: Math.round(perUserCost * jitter * 100) / 100,
      });
    }
  }
  return users;
}

function buildDailySpend(): DailySpendPoint[] {
  const rand = seeded(7);
  const days = 30;
  const points: DailySpendPoint[] = [];
  const start = new Date("2026-04-18T00:00:00Z").getTime();
  const teamWeights = TEAMS.map((t) => t.spent / 7390);
  const targetTotal = 7390;
  const dailyMean = targetTotal / days;

  const raw: number[] = [];
  let sum = 0;
  for (let d = 0; d < days; d++) {
    const dayOfWeek = (d + 5) % 7;
    const weekend = dayOfWeek === 0 || dayOfWeek === 6;
    const base = weekend ? 0.25 : 1.0;
    const noise = 0.7 + rand() * 0.7;
    const value = dailyMean * base * noise;
    raw.push(value);
    sum += value;
  }
  const scale = targetTotal / sum;
  for (let d = 0; d < days; d++) {
    const total = raw[d] * scale;
    const date = new Date(start + d * 86400000).toISOString().slice(0, 10);
    const byTeam: Record<string, number> = {};
    TEAMS.forEach((team, i) => {
      byTeam[team.name] = Math.round(total * teamWeights[i] * 100) / 100;
    });
    points.push({ date, total: Math.round(total * 100) / 100, byTeam });
  }
  return points;
}

function buildModelUsage(): ModelUsageSlice[] {
  return [
    {
      model: "claude-sonnet",
      tokens: 18_400_000,
      cost: 4_650,
      color: COLORS.sonnet,
    },
    {
      model: "claude-opus",
      tokens: 6_200_000,
      cost: 1_699,
      color: COLORS.opus,
    },
    {
      model: "claude-haiku",
      tokens: 9_800_000,
      cost: 1_041,
      color: COLORS.haiku,
    },
  ];
}

function buildModelTrend(daily: DailySpendPoint[]) {
  const rand = seeded(91);
  return daily.map((d) => {
    const sonnet = d.total * (0.55 + rand() * 0.1);
    const opus = d.total * (0.22 + rand() * 0.05);
    const haiku = d.total - sonnet - opus;
    return {
      date: d.date,
      sonnet: Math.round(sonnet * 100) / 100,
      opus: Math.round(opus * 100) / 100,
      haiku: Math.round(Math.max(0, haiku) * 100) / 100,
    };
  });
}

const TOPIC_DEFS: {
  task_type: TaskType;
  label: string;
  cost: number;
  sessions: number;
}[] = [
  {
    task_type: "feature-dev",
    label: "Feature Dev",
    cost: 2180,
    sessions: 2430,
  },
  { task_type: "bug-fix", label: "Bug Fixing", cost: 1620, sessions: 1810 },
  { task_type: "review", label: "Code Review", cost: 892, sessions: 1200 },
  { task_type: "refactor", label: "Refactoring", cost: 589, sessions: 680 },
  { task_type: "exploration", label: "Architecture", cost: 489, sessions: 410 },
  { task_type: "testing", label: "Testing", cost: 723, sessions: 820 },
  { task_type: "docs", label: "Docs", cost: 312, sessions: 290 },
  {
    task_type: "exploration",
    label: "Data Analysis",
    cost: 198,
    sessions: 240,
  },
  { task_type: "devops", label: "DevOps", cost: 289, sessions: 320 },
  { task_type: "config", label: "Config", cost: 98, sessions: 110 },
];

function buildTopics(): TopicBucket[] {
  return TOPIC_DEFS.map((t) => ({
    task_type: t.task_type,
    label: t.label,
    cost: t.cost,
    sessions: t.sessions,
    color: COLORS.taskColors[t.task_type],
  }));
}

function buildTopicMixByTeam() {
  const rand = seeded(303);
  const taskTypes: TaskType[] = [
    "feature-dev",
    "bug-fix",
    "review",
    "refactor",
    "docs",
    "testing",
    "devops",
    "exploration",
  ];
  return TEAMS.map((t) => {
    const values: Partial<Record<TaskType, number>> = {};
    let sum = 0;
    for (const tt of taskTypes) {
      const v = rand();
      values[tt] = v;
      sum += v;
    }
    for (const tt of taskTypes) {
      values[tt] = Math.round(((values[tt] || 0) / sum) * 100);
    }
    return { team: t.name, values: values as Record<TaskType, number> };
  });
}

const SKILLS: SkillCost[] = [
  { skill: "frontend-design", cost: 1204 },
  { skill: "review", cost: 891 },
  { skill: "opik:opik", cost: 723 },
  { skill: "opik:instrument", cost: 612 },
  { skill: "simplify", cost: 489 },
  { skill: "init", cost: 378 },
  { skill: "opik:trace-analysis", cost: 312 },
  { skill: "update-config", cost: 234 },
];

const MCPS = [
  { server: "GitHub", cost: 423 },
  { server: "Linear", cost: 312 },
  { server: "Sentry", cost: 234 },
  { server: "PostHog", cost: 189 },
  { server: "Slack", cost: 123 },
  { server: "Google Drive", cost: 89 },
];

const SIDE_PROJECTS: SideProjectRow[] = [
  {
    user_name: "James Smith",
    team: "Platform Engineering",
    repository: "jsmith/my-saas-app",
    sessions: 45,
    cost: 89.2,
    first_detected: "May 3, 2026",
  },
  {
    user_name: "Alan Chen",
    team: "Data Science",
    repository: "cryptotrader/algo-bot",
    sessions: 12,
    cost: 23.4,
    first_detected: "May 11, 2026",
  },
];

const EXPENSIVE_PROMPTS: ExpensivePromptRow[] = [
  {
    user_name: "Sarah Chen",
    team: "Platform Eng",
    when: "May 18 14:32",
    model: "claude-opus",
    cost: 2.84,
    tokens: 68_230,
    repository: "opik/opik-backend",
  },
  {
    user_name: "Maya Patel",
    team: "Frontend",
    when: "May 19 09:14",
    model: "claude-opus",
    cost: 2.41,
    tokens: 58_900,
    repository: "opik/opik-frontend",
  },
  {
    user_name: "Diego Romero",
    team: "Platform Engineering",
    when: "May 19 16:02",
    model: "claude-sonnet",
    cost: 2.02,
    tokens: 84_120,
    repository: "opik/opik-backend",
  },
  {
    user_name: "Priya Krishnan",
    team: "Data Science",
    when: "May 17 11:28",
    model: "claude-opus",
    cost: 1.91,
    tokens: 47_600,
    repository: "comet/ml-pipelines",
  },
  {
    user_name: "Marcus Johnson",
    team: "DevOps / SRE",
    when: "May 19 22:48",
    model: "claude-sonnet",
    cost: 1.76,
    tokens: 71_400,
    repository: "comet/infra-terraform",
  },
];

function buildHeatmap(): HeatmapCell[] {
  const rand = seeded(17);
  const cells: HeatmapCell[] = [];
  for (let day = 0; day < 7; day++) {
    const weekend = day === 0 || day === 6;
    for (let hour = 0; hour < 24; hour++) {
      let base = 0;
      if (hour >= 9 && hour <= 17) base = 60;
      else if (hour >= 7 && hour <= 19) base = 30;
      else if (hour >= 20 && hour <= 23) base = 12;
      else base = 4;
      if (weekend) base *= 0.35;
      const noise = 0.7 + rand() * 0.6;
      cells.push({ day, hour, sessions: Math.round(base * noise) });
    }
  }
  return cells;
}

function buildRecentTraces(users: UserSummary[]): ClaudeCodeTrace[] {
  const rand = seeded(99);
  const repos = [
    "opik/opik-backend",
    "opik/opik-frontend",
    "comet/ml-pipelines",
    "comet/infra-terraform",
    "comet/web-app",
  ];
  const models: ClaudeCodeTrace["model"][] = [
    "claude-sonnet",
    "claude-opus",
    "claude-haiku",
  ];
  const taskTypes: TaskType[] = [
    "feature-dev",
    "bug-fix",
    "review",
    "refactor",
    "testing",
    "docs",
  ];
  const skillNames = SKILLS.map((s) => s.skill);
  const subsystems = [
    "auth",
    "billing",
    "search",
    "ingest",
    "ui-components",
    "traces",
  ];
  const traces: ClaudeCodeTrace[] = [];
  for (let i = 0; i < 50; i++) {
    const u = users[Math.floor(rand() * users.length)];
    const r = repos[Math.floor(rand() * repos.length)];
    const m = models[Math.floor(rand() * models.length)];
    const inTokens = Math.floor(2000 + rand() * 50000);
    const outTokens = Math.floor(500 + rand() * 8000);
    traces.push({
      trace_id: `trace_${i.toString().padStart(4, "0")}`,
      user_id: u.user_id,
      user_name: u.user_name,
      model: m,
      input_tokens: inTokens,
      output_tokens: outTokens,
      cost:
        Math.round(((inTokens / 1e6) * 3 + (outTokens / 1e6) * 15) * 100) / 100,
      duration_ms: Math.floor(2000 + rand() * 480000),
      mcp_servers: rand() > 0.6 ? ["GitHub"] : [],
      tools_used: ["Bash", "Read", "Edit"].filter(() => rand() > 0.3),
      created_at: new Date(Date.now() - i * 3600_000).toISOString(),
      cc: {
        team: u.team,
        repository: r,
        task_type: taskTypes[Math.floor(rand() * taskTypes.length)],
        skill: skillNames[Math.floor(rand() * skillNames.length)],
        subsystem: subsystems[Math.floor(rand() * subsystems.length)],
        work_classification: rand() > 0.95 ? "side-project" : "work-repo",
        outcome: rand() > 0.85 ? "abandoned" : "completed",
        risk_flags: rand() > 0.9 ? ["destructive-command"] : [],
      },
    });
  }
  return traces;
}

// Aggregate token-spend composition across the org for the window.
// Calibrated from a single 1,267-LLM-call session ($257) and scaled to
// org-level spendMTD. Lanes are rolled up to top-level categories — drill
// into "MCP servers", "Tool calls", or "Static overhead" for per-item.
function buildTokenComposition(): TokenComposition {
  const mcpBreakdown: SankeyFlow[] = [
    { label: "Jira-Headless (49 tools)", value: 110, group: "mcp" },
    { label: "claude_ai_Atlassian (31 tools)", value: 78, group: "mcp" },
    { label: "chrome-devtools (29 tools)", value: 57, group: "mcp" },
    { label: "GitHub (37 tools)", value: 34, group: "mcp" },
    { label: "claude_ai_Notion (2 tools)", value: 5, group: "mcp" },
    { label: "claude_ai_Slack (2 tools)", value: 5, group: "mcp" },
    { label: "claude_ai_Google_Calendar (2 tools)", value: 5, group: "mcp" },
    { label: "claude_ai_Google_Drive (2 tools)", value: 4, group: "mcp" },
    { label: "claude_ai_Sentry (2 tools)", value: 4, group: "mcp" },
    { label: "claude_ai_Gmail (2 tools)", value: 3, group: "mcp" },
    { label: "claude_ai_PostHog (2 tools)", value: 3, group: "mcp" },
    { label: "8 other small MCPs", value: 9, group: "mcp" },
  ];
  const mcpTotal = mcpBreakdown.reduce((a, b) => a + b.value, 0);

  const toolBreakdown: SankeyFlow[] = [
    { label: "Edit", value: 54, group: "tool" },
    { label: "Bash", value: 49, group: "tool" },
    { label: "Write", value: 44, group: "tool" },
    { label: "TaskCreate / TaskUpdate", value: 4, group: "tool" },
    { label: "AskUserQuestion", value: 3, group: "tool" },
    { label: "Read", value: 2, group: "tool" },
    { label: "ToolSearch", value: 1, group: "tool" },
  ];
  const toolTotal = toolBreakdown.reduce((a, b) => a + b.value, 0);

  // Loaded skill bodies — when the model invokes the Skill tool, the SKILL.md
  // body comes back as a tool_result and gets re-sent every subsequent turn.
  // Carved out of "tool_results" since these are conceptually different from
  // bash/file reads.
  const skillsLoadedBreakdown: SankeyFlow[] = [
    {
      label: "claude-api (Anthropic SDK reference)",
      value: 86,
      group: "skill",
    },
    { label: "opik-frontend (FE patterns)", value: 34, group: "skill" },
    { label: "opik-backend (Java patterns)", value: 28, group: "skill" },
    { label: "playwright-e2e (test workflow)", value: 22, group: "skill" },
    { label: "python-sdk (Python SDK)", value: 19, group: "skill" },
    { label: "typescript-sdk (TS SDK)", value: 17, group: "skill" },
    { label: "claude-code-guide", value: 12, group: "skill" },
    { label: "local-dev / documentation / verify", value: 18, group: "skill" },
    { label: "9 other skills (rarely loaded)", value: 14, group: "skill" },
  ];
  const skillsLoadedTotal = skillsLoadedBreakdown.reduce(
    (a, b) => a + b.value,
    0,
  );

  // MCP tool INVOCATIONS — what the model spent emitting MCP tool_use blocks.
  // Distinct from MCP-servers-loaded (input overhead) — this is the actual
  // cost when Claude calls jira.create_issue, github.list_prs, etc.
  const mcpCallsBreakdown: SankeyFlow[] = [
    { label: "Jira-Headless · jira_search / create", value: 16, group: "mcp" },
    {
      label: "GitHub · create_pull_request / search_code",
      value: 9,
      group: "mcp",
    },
    { label: "claude_ai_Atlassian · search / fetch", value: 6, group: "mcp" },
    {
      label: "chrome-devtools · navigate / screenshot",
      value: 4,
      group: "mcp",
    },
    { label: "claude_ai_Slack / Notion / Gmail", value: 3, group: "mcp" },
    { label: "8 other MCPs (rarely called)", value: 2, group: "mcp" },
  ];
  const mcpCallsTotal = mcpCallsBreakdown.reduce((a, b) => a + b.value, 0);

  // Skill invocations — the model emitting `Skill` tool_use blocks to load a
  // skill body into context. Tiny on output side; the BIG cost is the body
  // that comes back (skillsLoadedBreakdown above).
  const skillCallsBreakdown: SankeyFlow[] = [
    { label: "claude-api invocations", value: 4, group: "skill" },
    { label: "opik-frontend invocations", value: 3, group: "skill" },
    { label: "find-skills invocations", value: 2, group: "skill" },
    { label: "playwright-e2e invocations", value: 2, group: "skill" },
    { label: "other skill invocations", value: 3, group: "skill" },
  ];
  const skillCallsTotal = skillCallsBreakdown.reduce((a, b) => a + b.value, 0);

  const staticBreakdown: SankeyFlow[] = [
    { label: "Base Claude Code prompt + wrap", value: 202, group: "static" },
    { label: "Skill listings (menu)", value: 31, group: "static" },
    { label: "Memory files (CLAUDE.md + memory/)", value: 25, group: "static" },
    { label: "Built-in tool names (deferred)", value: 12, group: "static" },
  ];
  const staticTotal = staticBreakdown.reduce((a, b) => a + b.value, 0);

  // Prior assistant context — how the conversation-history bill stacks up
  // across session lengths. The long-running sessions dominate.
  const priorAssistantBreakdown: SankeyFlow[] = [
    { label: "Sessions ≥ 200 turns", value: 1124, group: "dynamic" },
    { label: "Sessions 100–200 turns", value: 846, group: "dynamic" },
    { label: "Sessions 50–100 turns", value: 498, group: "dynamic" },
    { label: "Sessions < 50 turns", value: 346, group: "dynamic" },
  ];

  // Tool results — which tool dumped the most context back into the prompt.
  const toolResultsBreakdown: SankeyFlow[] = [
    { label: "Bash · verbose stdout/stderr", value: 812, group: "tool" },
    { label: "Read · file contents", value: 498, group: "tool" },
    { label: "Grep / Glob · search hits", value: 287, group: "tool" },
    { label: "WebFetch · HTML/markdown", value: 264, group: "tool" },
    { label: "Edit · diff results", value: 134, group: "tool" },
    {
      label: "Write / NotebookEdit · echoed contents",
      value: 103,
      group: "tool",
    },
  ];

  // User prompts — distribution by prompt size, so users can spot if a handful
  // of huge prompts are inflating their input.
  const userPromptsBreakdown: SankeyFlow[] = [
    {
      label: "Huge (>8k chars · pasted logs, PRDs)",
      value: 124,
      group: "dynamic",
    },
    { label: "Large (2k–8k chars)", value: 97, group: "dynamic" },
    { label: "Medium (500–2k chars)", value: 86, group: "dynamic" },
    { label: "Small (<500 chars)", value: 48, group: "dynamic" },
  ];

  // File attachments — what's actually being attached. Screenshots dominate.
  const fileAttachmentsBreakdown: SankeyFlow[] = [
    { label: "Screenshots / images", value: 38, group: "dynamic" },
    { label: "Source files (attached, not Read)", value: 24, group: "dynamic" },
    { label: "Logs / build output", value: 15, group: "dynamic" },
    { label: "PDFs / docs", value: 9, group: "dynamic" },
    { label: "CSVs / datasets", value: 6, group: "dynamic" },
  ];

  // Thinking — (model, effort) matrix. Where the $1,054/mo of thinking goes.
  // Opus 4.7 · xhigh is the giant.
  const thinkingBreakdown: SankeyFlow[] = [
    { label: "Opus 4.7 · effort xhigh", value: 720, group: "thinking" },
    { label: "Opus 4.7 · effort high", value: 189, group: "thinking" },
    { label: "Opus 4.7 · effort max", value: 84, group: "thinking" },
    { label: "Opus 4.7 · effort medium", value: 42, group: "thinking" },
    { label: "Sonnet 4.6 · effort high", value: 14, group: "thinking" },
    { label: "Sonnet 4.6 · effort medium", value: 5, group: "thinking" },
  ];

  // Assistant text — what kind of prose Claude is emitting.
  const assistantTextBreakdown: SankeyFlow[] = [
    { label: "Code blocks (final code in chat)", value: 19, group: "text" },
    { label: "Explanations / step-by-step", value: 12, group: "text" },
    { label: "Summaries / status updates", value: 8, group: "text" },
    { label: "Lists / bulleted output", value: 5, group: "text" },
    { label: "Short acknowledgements", value: 4, group: "text" },
  ];

  const input: SankeyFlow[] = [
    {
      label: "Prior assistant context",
      value: 2814,
      group: "dynamic",
      drillKey: "prior_assistant",
    },
    {
      label: "Tool results",
      value: 2098,
      group: "dynamic",
      drillKey: "tool_results",
    },
    {
      label: "User prompts",
      value: 355,
      group: "dynamic",
      drillKey: "user_prompts",
    },
    {
      label: `Skills loaded · ${skillsLoadedBreakdown.length}`,
      value: skillsLoadedTotal,
      group: "skill",
      drillKey: "skills_loaded",
    },
    {
      label: `MCP servers · ${mcpBreakdown.length} active`,
      value: mcpTotal,
      group: "mcp",
      drillKey: "mcp",
    },
    {
      label: "File attachments",
      value: 92,
      group: "dynamic",
      drillKey: "file_attachments",
    },
    {
      label: "Static overhead",
      value: staticTotal,
      group: "static",
      drillKey: "static",
    },
  ];
  const output: SankeyFlow[] = [
    {
      label: "Thinking",
      value: 1054,
      group: "thinking",
      drillKey: "thinking",
    },
    {
      label: "Built-in tool calls",
      value: toolTotal,
      group: "tool",
      drillKey: "tools",
    },
    {
      label: "Assistant text",
      value: 48,
      group: "text",
      drillKey: "assistant_text",
    },
    {
      label: "MCP tool calls",
      value: mcpCallsTotal,
      group: "mcp",
      drillKey: "mcp_calls",
    },
    {
      label: "Skill invocations",
      value: skillCallsTotal,
      group: "skill",
      drillKey: "skill_calls",
    },
  ];

  const totalInput = input.reduce((a, b) => a + b.value, 0);
  const totalOutput = output.reduce((a, b) => a + b.value, 0);
  return {
    input,
    output,
    totalInput,
    totalOutput,
    mcpBreakdown,
    toolBreakdown,
    staticBreakdown,
    skillsLoadedBreakdown,
    mcpCallsBreakdown,
    skillCallsBreakdown,
    priorAssistantBreakdown,
    toolResultsBreakdown,
    userPromptsBreakdown,
    fileAttachmentsBreakdown,
    thinkingBreakdown,
    assistantTextBreakdown,
  };
}

function buildRecommendations(): Recommendation[] {
  return [
    {
      id: "rec-compact-history",
      title: "Tighten /compact threshold on long sessions",
      body: "Prior assistant context costs $2,814/mo — 45% of input and the biggest single lane. 8 users average 180+ turns per session before compacting. Auto-compact at 50% (vs default 75%) cuts re-sent history 25–35%.",
      estSavingsMonthly: 640,
      severity: "high",
      actionLabel: "Update team config",
    },
    {
      id: "rec-thinking-effort",
      title: "Drop effort xhigh → high for routine Claude Code sessions",
      body: "Thinking is $1,054/mo — 80% of all output cost. 12 high-volume users default to effort=xhigh on every session; moving routine work (log triage, docs, test runs) to effort=high cuts thinking tokens 30–45% with negligible quality impact.",
      estSavingsMonthly: 420,
      severity: "high",
      actionLabel: "Review users",
    },
    {
      id: "rec-tool-outputs",
      title: "Compress verbose tool results",
      body: "Tool results are $2,098/mo — 34% of input, the second-largest lane. Bash/Read outputs dump full logs and entire files when only a few lines matter. Add `head`, `--quiet`, or response-size limits for the top 5 heaviest tool patterns.",
      estSavingsMonthly: 310,
      severity: "medium",
      actionLabel: "Set output limits",
    },
    {
      id: "rec-mcp-prune",
      title: "Disable 4 unused MCP servers for non-users",
      body: "12 MCP servers loaded org-wide cost $317/mo in pure context overhead, before a single tool is called. Four (jira-headless, lusha, gamma, zoominfo) are used by <5% of users — keep them on for those few, disable everywhere else.",
      estSavingsMonthly: 180,
      severity: "medium",
      actionLabel: "Disable for non-users",
    },
    {
      id: "rec-skills-on-demand",
      title: "Move 4 niche Skills to on-demand load",
      body: "9 Skills auto-loaded per session at $250/mo. claude-api + opik-frontend account for $120 and are widely used. The bottom 4 (browser-use, sales-research, vendor-portal, oncall-runbook) are invoked <3× per user per month — switch them to plugin-only invocation.",
      estSavingsMonthly: 140,
      severity: "medium",
      actionLabel: "Move to on-demand",
    },
  ];
}

export function buildClaudeCodeData(
  windowDays: 7 | 30 | 90 = 30,
): ClaudeCodeData {
  const users = buildUsers();
  const dailySpend = buildDailySpend();
  const modelUsage = buildModelUsage();
  const modelTrend = buildModelTrend(dailySpend);
  const topics = buildTopics();
  const topicMixByTeam = buildTopicMixByTeam();
  const heatmap = buildHeatmap();
  const recentTraces = buildRecentTraces(users);

  return {
    asOf: "2026-05-19",
    windowDays,
    org: {
      spendMTD: 7390,
      budget: 8000,
      activeUsers: 28,
      totalUsers: 30,
      activeTeams: 6,
      totalSessions: 8241,
      avgCostPerUser: 264,
      flaggedRepoCount: 2,
      overBudgetTeamName: "Platform Engineering",
      overBudgetTeamPct: 91,
      offHoursSessions: 47,
    },
    teams: TEAMS,
    users,
    dailySpend,
    modelUsage,
    topics,
    topicMixByTeam,
    topSkills: SKILLS,
    mcpCosts: MCPS,
    costliestSkill: SKILLS[0],
    costliestMcp: MCPS[0],
    opusUsagePct: 23,
    opusUsageCost: 1699,
    modelTrend,
    sideProjects: SIDE_PROJECTS,
    expensivePrompts: EXPENSIVE_PROMPTS,
    heatmap,
    recentTraces,
    tokenComposition: buildTokenComposition(),
    recommendations: buildRecommendations(),
  };
}
