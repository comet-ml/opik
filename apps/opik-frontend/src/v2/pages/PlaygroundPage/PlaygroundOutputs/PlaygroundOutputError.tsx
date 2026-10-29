import React from "react";
import { CircleX, Lightbulb } from "lucide-react";

import { cn } from "@/lib/utils";
import { RunFailureHint } from "@/lib/playground";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface PlaygroundOutputErrorProps {
  message: string;
  hint?: RunFailureHint;
  stale?: boolean;
}

const PlaygroundOutputError: React.FC<PlaygroundOutputErrorProps> = ({
  message,
  hint,
  stale = false,
}) => (
  <div className="flex flex-col items-start gap-1">
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
          <span className="font-medium">Run failed:</span>{" "}
          {hint?.title ?? message}
        </span>
      </span>
    </TooltipWrapper>
    {hint && (
      <span
        data-testid="playground-output-error-action"
        className={cn(
          "comet-body-xs flex items-start gap-1 text-muted-slate",
          stale && "opacity-50",
        )}
      >
        <Lightbulb className="mt-0.5 size-3 shrink-0" />
        {hint.action}
      </span>
    )}
  </div>
);

export default PlaygroundOutputError;
