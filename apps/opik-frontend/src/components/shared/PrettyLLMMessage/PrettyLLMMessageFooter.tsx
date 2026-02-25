import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageFooterProps } from "./types";

const PrettyLLMMessageFooter: React.FC<PrettyLLMMessageFooterProps> = ({
  usage,
  finishReason,
  className,
}) => {
  // Don't render if no data
  if (!usage && !finishReason) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex items-center gap-4 text-xs text-muted-foreground",
        className,
      )}
    >
      {usage && (
        <div className="flex items-center gap-2">
          <span className="font-medium">Max completion tokens</span>
          <span>{usage.completion_tokens || 0}</span>
        </div>
      )}
      {finishReason && (
        <div className="flex items-center gap-2">
          <span className="font-medium">Finish reason</span>
          <span className="capitalize">{finishReason}</span>
        </div>
      )}
    </div>
  );
};

export default PrettyLLMMessageFooter;
