import React from "react";
import { CircleX } from "lucide-react";

import { cn } from "@/lib/utils";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface PlaygroundOutputErrorProps {
  message: string;
  stale?: boolean;
}

const PlaygroundOutputError: React.FC<PlaygroundOutputErrorProps> = ({
  message,
  stale = false,
}) => (
  <TooltipWrapper content={message}>
    <span
      data-testid="playground-output-error"
      className={cn(
        "inline-flex max-w-full cursor-default items-center gap-1 rounded-md border border-transparent bg-[var(--tag-red-bg)] px-1.5 py-0.5 text-sm text-[var(--tag-red-text)]",
        stale && "opacity-50",
      )}
    >
      <CircleX className="size-3 shrink-0" />
      <span className="min-w-0 truncate">
        <span className="font-medium">Run failed:</span> {message}
      </span>
    </span>
  </TooltipWrapper>
);

export default PlaygroundOutputError;
