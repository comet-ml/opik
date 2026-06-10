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
  Wrench,
  Zap,
} from "lucide-react";

export interface LaneMeta {
  icon: LucideIcon;
  color: string;
  labelFallback: string;
}

const REGISTRY: Record<string, LaneMeta> = {
  prior_assistant: {
    icon: ScrollText,
    color: "#6bdf93",
    labelFallback: "Prior assistant context",
  },
  tool_results: {
    icon: Wrench,
    color: "#89deff",
    labelFallback: "Tool results",
  },
  user_prompts: {
    icon: MessageCircle,
    color: "#7c3aed",
    labelFallback: "User prompts",
  },
  skills_available: {
    icon: Library,
    color: "#f59e0b",
    labelFallback: "Skills available",
  },
  skills_loaded: {
    icon: Sparkles,
    color: "#db46ef",
    labelFallback: "Skills loaded",
  },
  tools: {
    icon: Wrench,
    color: "#06b6d4",
    labelFallback: "Tools schema",
  },
  memory: {
    icon: NotebookText,
    color: "#64748b",
    labelFallback: "Memory",
  },
  file_attachments: {
    icon: Paperclip,
    color: "#5155f5",
    labelFallback: "File attachments",
  },
  mcp_servers: {
    icon: Plug,
    color: "#ef4444",
    labelFallback: "MCP servers",
  },
  static_overhead: {
    icon: Layers,
    color: "#19a979",
    labelFallback: "Static overhead",
  },
  claude_code: {
    icon: Bot,
    color: "#373d4d",
    labelFallback: "Claude Code",
  },
  codex: { icon: Bot, color: "#0f172a", labelFallback: "Codex" },
  cursor: { icon: Bot, color: "#64748b", labelFallback: "Cursor" },
  thinking: {
    icon: Brain,
    color: "#6bdf93",
    labelFallback: "Thinking",
  },
  assistant_text: {
    icon: BotMessageSquare,
    color: "#7c3aed",
    labelFallback: "Assistant text",
  },
  built_in_tool_calls: {
    icon: Wrench,
    color: "#89deff",
    labelFallback: "Built-in tool calls",
  },
  mcp_tool_calls: {
    icon: Terminal,
    color: "#ef4444",
    labelFallback: "MCP tool calls",
  },
  skill_invocations: {
    icon: Sparkles,
    color: "#db46ef",
    labelFallback: "Skill invocations",
  },
};

const DEFAULT_META: LaneMeta = {
  icon: Zap,
  color: "#64748b",
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
