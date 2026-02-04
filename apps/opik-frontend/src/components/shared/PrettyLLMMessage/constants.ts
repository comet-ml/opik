import {
  Settings,
  User,
  Bot,
  Wrench,
  LucideIcon,
  CodeIcon,
} from "lucide-react";

export type RoleConfig = {
  icon: LucideIcon;
  label: string;
  iconColor: string;
  iconBgColor: string;
};

export const ROLE_CONFIG: Record<
  "system" | "user" | "assistant" | "tool" | "function",
  RoleConfig
> = {
  system: {
    icon: Settings,
    label: "System",
    iconColor: "text-[var(--tag-blue-text)]",
    iconBgColor: "bg-[var(--tag-blue-bg)]",
  },
  user: {
    icon: User,
    label: "User",
    iconColor: "text-[var(--tag-turquoise-text)]",
    iconBgColor: "bg-[var(--tag-turquoise-bg)]",
  },
  assistant: {
    icon: Bot,
    label: "Assistant",
    iconColor: "text-[var(--tag-yellow-text)]",
    iconBgColor: "bg-[var(--tag-yellow-bg)]",
  },
  tool: {
    icon: Wrench,
    label: "Tool",
    iconColor: "text-[var(--tag-burgundy-text)]",
    iconBgColor: "bg-[var(--tag-burgundy-bg)]",
  },
  function: {
    icon: CodeIcon,
    label: "Function",
    iconColor: "text-[var(--tag-green-text)]",
    iconBgColor: "bg-[var(--tag-green-bg)]",
  },
};
