import { Settings, User, Bot, Wrench, LucideIcon } from "lucide-react";

export type RoleConfig = {
  icon: LucideIcon;
  label: string;
  iconColor: string;
  iconBgColor: string;
};

export const ROLE_CONFIG: Record<
  "system" | "user" | "assistant" | "tool",
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
    iconColor: "text-[var(--tag-purple-text)]",
    iconBgColor: "bg-[var(--tag-purple-bg)]",
  },
};
