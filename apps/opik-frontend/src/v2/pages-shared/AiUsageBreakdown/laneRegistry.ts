import {
  Bot,
  BotMessageSquare,
  Brain,
  Layers,
  Library,
  type LucideIcon,
  MessageCircle,
  NotebookText,
  Paperclip,
  Plug,
  ScrollText,
  Sparkles,
  Terminal,
  UserCog,
  Wrench,
  Zap,
} from "lucide-react";

export interface LaneMeta {
  icon: LucideIcon;
  color: string;
  iconColor: string;
  labelFallback: string;
  // Shown in the lane breakdown side panel. Numbers mean "input tokens
  // billed" (replay-weighted), so copy phrases cost accordingly.
  description?: string;
}

const REGISTRY: Record<string, LaneMeta> = {
  prior_assistant: {
    icon: ScrollText,
    color: "#6bdf93",
    iconColor: "text-foreground",
    labelFallback: "Prior assistant context",
    description:
      "Tokens billed replaying Claude's own earlier text and thinking - the cost of session length. Reduce with shorter sessions, /clear and /compact.",
  },
  tool_results: {
    icon: Wrench,
    color: "#89deff",
    iconColor: "text-foreground",
    labelFallback: "Tool results",
  },
  // Successor of tool_results in the 9-lane model: built-in tool output only
  // (MCP results move to mcp_servers). Inherits its color.
  built_in_tools: {
    icon: Wrench,
    color: "#89deff",
    iconColor: "text-foreground",
    labelFallback: "Built-in tools",
    description:
      "Tokens billed for built-in tool output (Bash, Read, Grep...), re-billed each subsequent turn. Tool schemas live under Static overhead.",
  },
  user_prompts: {
    icon: MessageCircle,
    color: "#7c3aed",
    iconColor: "text-white",
    labelFallback: "User prompts",
    description:
      "Tokens billed for the text your team typed, re-billed on every turn after it.",
  },
  skills_available: {
    icon: Library,
    color: "#f59e0b",
    iconColor: "text-foreground",
    labelFallback: "Skills available",
  },
  // Merged successor of skills_available + skills_loaded (menu + bodies).
  skills: {
    icon: Sparkles,
    color: "#f59e0b",
    iconColor: "text-foreground",
    labelFallback: "Skills",
    description:
      "Tokens billed for skills: the always-on menu entry of every installed skill, plus the full body of any skill loaded on use.",
  },
  custom_agents: {
    icon: UserCog,
    color: "#06b6d4",
    iconColor: "text-white",
    labelFallback: "Custom agents",
    description:
      "Tokens billed for subagent dispatch descriptions, riding on every request whether used or not.",
  },
  skills_loaded: {
    icon: Sparkles,
    color: "#db46ef",
    iconColor: "text-white",
    labelFallback: "Skills loaded",
  },
  tools: {
    icon: Wrench,
    color: "#06b6d4",
    iconColor: "text-white",
    labelFallback: "Tools schema",
  },
  memory: {
    icon: NotebookText,
    color: "#64748b",
    iconColor: "text-white",
    labelFallback: "Memory",
    description:
      "Tokens billed for project instructions - CLAUDE.md, rules files and auto-memory - loaded into every request in the repo.",
  },
  file_attachments: {
    icon: Paperclip,
    color: "#5155f5",
    iconColor: "text-white",
    labelFallback: "File attachments",
    description:
      "Tokens billed for attached files and images, resent in every request after they're attached.",
  },
  mcp_servers: {
    icon: Plug,
    color: "#ef4444",
    iconColor: "text-white",
    labelFallback: "MCP servers",
    description:
      "Tokens billed for MCP servers: schemas and instructions on every request (definition), plus tool-call usage.",
  },
  unattributed: {
    icon: Zap,
    color: "#94a3b8",
    iconColor: "text-white",
    labelFallback: "Unattributed",
    description:
      "Billed tokens not yet attributable to a lane: system reminders, request envelope and residual estimation drift. Shrinks as anchoring improves.",
  },
  static_overhead: {
    icon: Layers,
    color: "#19a979",
    iconColor: "text-white",
    labelFallback: "Static overhead",
    description:
      "Tokens billed for Claude Code itself: core prompt, environment block (cwd + git status), built-in tool schemas and the deferred-tool name index. Fixed per Claude Code version; the environment item grows with your repo's git status.",
  },
  claude_code: {
    icon: Bot,
    color: "#373d4d",
    iconColor: "text-white",
    labelFallback: "Claude Code",
  },
  codex: {
    icon: Bot,
    color: "#0f172a",
    iconColor: "text-white",
    labelFallback: "Codex",
  },
  cursor: {
    icon: Bot,
    color: "#64748b",
    iconColor: "text-white",
    labelFallback: "Cursor",
  },
  thinking: {
    icon: Brain,
    color: "#6bdf93",
    iconColor: "text-foreground",
    labelFallback: "Thinking",
    description: "Output tokens generated as extended thinking blocks.",
  },
  assistant_text: {
    icon: BotMessageSquare,
    color: "#7c3aed",
    iconColor: "text-white",
    labelFallback: "Assistant text",
    description: "Output tokens for visible text responses.",
  },
  built_in_tool_calls: {
    icon: Wrench,
    color: "#89deff",
    iconColor: "text-foreground",
    labelFallback: "Built-in tool calls",
    description: "Output tokens spent writing built-in tool calls.",
  },
  mcp_tool_calls: {
    icon: Terminal,
    color: "#ef4444",
    iconColor: "text-white",
    labelFallback: "MCP tool calls",
    description: "Output tokens spent writing MCP tool calls.",
  },
  skill_invocations: {
    icon: Sparkles,
    color: "#db46ef",
    iconColor: "text-white",
    labelFallback: "Skill invocations",
    description: "Output tokens spent invoking skills.",
  },
};

const DEFAULT_META: LaneMeta = {
  icon: Zap,
  color: "#64748b",
  iconColor: "text-white",
  labelFallback: "",
};

export const getLaneMeta = (key: string, fallbackLabel?: string): LaneMeta => {
  const meta = REGISTRY[key];
  if (meta) return meta;
  return {
    ...DEFAULT_META,
    labelFallback: fallbackLabel ?? key,
  };
};
