import React from "react";
import capitalize from "lodash/capitalize";

import { cn } from "@/lib/utils";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

export const getRoleLabel = (role: unknown): string => {
  if (typeof role !== "string") return String(role ?? "");
  const roleKey = role.toUpperCase() as keyof typeof LLM_MESSAGE_ROLE;
  if (LLM_MESSAGE_ROLE[roleKey]) {
    return LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE[roleKey]] || role;
  }
  return capitalize(role);
};

type ChatMessageCardProps = {
  role: string;
  badges?: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
};

const ChatMessageCard: React.FC<ChatMessageCardProps> = ({
  role,
  badges,
  className,
  children,
}) => (
  <div
    className={cn(
      "flex flex-col gap-1 rounded-md border bg-primary-foreground p-2",
      className,
    )}
  >
    <div className="flex items-center gap-2">
      <span className="comet-body-xs text-muted-slate">
        {getRoleLabel(role)}
      </span>
      {badges}
    </div>
    {children}
  </div>
);

export default ChatMessageCard;
