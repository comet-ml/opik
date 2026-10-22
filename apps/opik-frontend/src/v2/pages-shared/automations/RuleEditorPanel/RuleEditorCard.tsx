import React from "react";

import { cn } from "@/lib/utils";

type RuleEditorCardProps = {
  header: React.ReactNode;
  hasError?: boolean;
  children: React.ReactNode;
};

const RuleEditorCard: React.FC<RuleEditorCardProps> = ({
  header,
  hasError = false,
  children,
}) => (
  <div
    className={cn(
      "flex flex-1 flex-col overflow-hidden rounded-md border border-border",
      hasError && "border-destructive",
    )}
  >
    <div className="flex h-8 shrink-0 items-center border-b border-border bg-soft-background py-1 pl-3 pr-2">
      {header}
    </div>
    {children}
  </div>
);

export default RuleEditorCard;
