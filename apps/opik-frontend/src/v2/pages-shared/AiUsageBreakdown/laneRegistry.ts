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
  iconColor: string;
  labelFallback: string;
}

const REGISTRY: Record<string, LaneMeta> = {
  prior_assistant: {
    icon: ScrollText,
    color: "#6bdf93",
    iconColor: "text-foreground",
    labelFallback: "Prior assistant context",
  },
  tool_results: {
    icon: Wrench,
    color: "#89deff",
    iconColor: "text-foreground",
    labelFallback: "Tool results",
  },
  user_prompts: {
    icon: MessageCircle,
    color: "#7c3aed",
    iconColor: "text-white",
    labelFallback: "User prompts",
  },
  skills_available: {
    icon: Library,
    color: "#f59e0b",
    iconColor: "text-foreground",
    labelFallback: "Skills available",
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
  },
  file_attachments: {
    icon: Paperclip,
    color: "#5155f5",
    iconColor: "text-white",
    labelFallback: "File attachments",
  },
  mcp_servers: {
    icon: Plug,
    color: "#ef4444",
    iconColor: "text-white",
    labelFallback: "MCP servers",
  },
  static_overhead: {
    icon: Layers,
    color: "#19a979",
    iconColor: "text-white",
    labelFallback: "Static overhead",
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
  },
  assistant_text: {
    icon: BotMessageSquare,
    color: "#7c3aed",
    iconColor: "text-white",
    labelFallback: "Assistant text",
  },
  built_in_tool_calls: {
    icon: Wrench,
    color: "#89deff",
    iconColor: "text-foreground",
    labelFallback: "Built-in tool calls",
  },
  mcp_tool_calls: {
    icon: Terminal,
    color: "#ef4444",
    iconColor: "text-white",
    labelFallback: "MCP tool calls",
  },
  skill_invocations: {
    icon: Sparkles,
    color: "#db46ef",
    iconColor: "text-white",
    labelFallback: "Skill invocations",
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
