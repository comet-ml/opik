import React from "react";
import { getDisplayUsage } from "./usage";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageUsageProps } from "./types";

const PrettyLLMMessageUsage: React.FC<PrettyLLMMessageUsageProps> = ({
  usage,
  className,
}) => {
  const entries = getDisplayUsage(usage);
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
      {entries.map(({ key, value, label }) => (
        <div key={key} className="flex items-center gap-2">
          <span className="font-medium">{label}</span>
          <span>{value}</span>
        </div>
      ))}
    </div>
  );
};

export default PrettyLLMMessageUsage;
