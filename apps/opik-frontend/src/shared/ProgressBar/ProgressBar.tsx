import React from "react";
import { cn } from "@/lib/utils";

export interface ProgressBarProps {
  value: number;
  color?: string;
  className?: string;
}

const ProgressBar: React.FC<ProgressBarProps> = ({
  value,
  color,
  className,
}) => (
  <div
    className={cn(
      "relative h-1.5 w-28 shrink-0 overflow-hidden rounded-full bg-border",
      className,
    )}
  >
    <div
      className="absolute inset-y-0 left-0 rounded-full bg-primary"
      style={{
        width: `${Math.min(Math.max(value, 0), 100)}%`,
        ...(color ? { backgroundColor: color } : {}),
      }}
    />
  </div>
);

export default ProgressBar;
