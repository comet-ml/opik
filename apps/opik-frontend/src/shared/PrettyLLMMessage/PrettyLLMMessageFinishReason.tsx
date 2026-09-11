import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageFinishReasonProps } from "./types";

const PrettyLLMMessageFinishReason: React.FC<
  PrettyLLMMessageFinishReasonProps
> = ({ finishReason, className }) => {
  if (!finishReason) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex items-center gap-2 text-xs text-muted-foreground",
        className,
      )}
    >
      <span className="font-medium">Finish reason</span>
      <span className="capitalize">{finishReason}</span>
    </div>
  );
};

export default PrettyLLMMessageFinishReason;
