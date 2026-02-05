import React from "react";
import { cn } from "@/lib/utils";

type EnvironmentBadgeProps = {
  env: string;
  className?: string;
};

const ENV_COLORS: Record<string, string> = {
  latest: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400",
  prod: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400",
  stage: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400",
  qa: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400",
};

const DEFAULT_COLOR = "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-400";

const EnvironmentBadge: React.FC<EnvironmentBadgeProps> = ({
  env,
  className,
}) => {
  const colorClass = ENV_COLORS[env.toLowerCase()] || DEFAULT_COLOR;

  return (
    <span
      className={cn(
        "inline-flex items-center rounded-sm px-1.5 py-0.5 text-xs font-medium uppercase",
        colorClass,
        className,
      )}
    >
      {env}
    </span>
  );
};

export default EnvironmentBadge;
