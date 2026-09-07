import React from "react";
import startCase from "lodash/startCase";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageUsageProps } from "./types";

const PrettyLLMMessageUsage: React.FC<PrettyLLMMessageUsageProps> = ({
  usage,
  className,
}) => {
  const entries = Object.entries(usage ?? {}).filter(
    ([, value]) => typeof value === "number" && Number.isFinite(value),
  );
  if (entries.length === 0) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-4 text-xs text-muted-foreground",
        className,
      )}
    >
      {entries.map(([key, value]) => (
        <div key={key} className="flex items-center gap-2">
          <span className="font-medium">
            {key === "completion_tokens" ? "Completion tokens" : startCase(key)}
          </span>
          <span>{value}</span>
        </div>
      ))}
    </div>
  );
};

export default PrettyLLMMessageUsage;
