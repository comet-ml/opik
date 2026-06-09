import {
  Bot,
  Brain,
  History,
  Layers,
  type LucideIcon,
  MessageCircleReply,
  Paperclip,
  Plug,
  Sparkles,
  Terminal,
  User,
  Wrench,
  Zap,
} from "lucide-react";

export interface LaneMeta {
  icon: LucideIcon;
  // Concrete color usable both for the icon chip and the SVG ribbon stroke.
  color: string;
  labelFallback: string;
}

// Keyed off the STABLE backend lane key (not the label) so labels can change
// server-side without breaking the mapping. Covers current + future lanes.
const REGISTRY: Record<string, LaneMeta> = {
  // input
  prior_assistant: {
    icon: History,
    color: "hsl(217 89% 58%)",
    labelFallback: "Prior assistant context",
  },
  tool_results: {
    icon: Terminal,
    color: "hsl(22 92% 55%)",
    labelFallback: "Tool results",
  },
  user_prompts: {
    icon: User,
    color: "hsl(165 75% 40%)",
    labelFallback: "User prompts",
  },
  skills_loaded: {
    icon: Sparkles,
    color: "hsl(48 96% 50%)",
    labelFallback: "Skills loaded",
  },
  mcp_servers: {
    icon: Plug,
    color: "hsl(265 70% 60%)",
    labelFallback: "MCP servers",
  },
  file_attachments: {
    icon: Paperclip,
    color: "hsl(330 75% 60%)",
    labelFallback: "File attachments",
  },
  static_overhead: {
    icon: Layers,
    color: "hsl(225 14% 50%)",
    labelFallback: "Static overhead",
  },
  // harness
  claude_code: {
    icon: Bot,
    color: "hsl(22 92% 55%)",
    labelFallback: "Claude Code",
  },
  codex: { icon: Bot, color: "hsl(0 0% 20%)", labelFallback: "Codex" },
  cursor: { icon: Bot, color: "hsl(0 0% 40%)", labelFallback: "Cursor" },
  // output
  thinking: {
    icon: Brain,
    color: "hsl(287 85% 56%)",
    labelFallback: "Thinking",
  },
  assistant_text: {
    icon: MessageCircleReply,
    color: "hsl(165 75% 40%)",
    labelFallback: "Assistant text",
  },
  built_in_tool_calls: {
    icon: Wrench,
    color: "hsl(22 92% 55%)",
    labelFallback: "Built-in tool calls",
  },
  mcp_tool_calls: {
    icon: Plug,
    color: "hsl(265 70% 60%)",
    labelFallback: "MCP tool calls",
  },
  skill_invocations: {
    icon: Sparkles,
    color: "hsl(48 96% 50%)",
    labelFallback: "Skill invocations",
  },
};

const DEFAULT_META: LaneMeta = {
  icon: Zap,
  color: "hsl(225 14% 50%)",
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
