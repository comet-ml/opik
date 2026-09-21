import React from "react";
import { CircleX } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface PlaygroundOutputErrorProps {
  message: string;
}

const PlaygroundOutputError: React.FC<PlaygroundOutputErrorProps> = ({
  message,
}) => (
  <TooltipWrapper content={message}>
    <span
      role="alert"
      className="inline-flex max-w-full cursor-default items-center gap-1 rounded-md border border-transparent bg-[var(--tag-red-bg)] px-1.5 py-0.5 text-sm text-[var(--tag-red-text)]"
    >
      <CircleX className="size-3 shrink-0" />
      <span className="min-w-0 truncate">
        <span className="font-medium">Run failed:</span> {message}
      </span>
    </span>
  </TooltipWrapper>
);

export default PlaygroundOutputError;
