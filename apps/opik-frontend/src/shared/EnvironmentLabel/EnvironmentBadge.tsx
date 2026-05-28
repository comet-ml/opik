import React from "react";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { TagProps } from "@/ui/tag";
import { cn } from "@/lib/utils";
import { getContrastingTextColor, resolveEnvironmentColor } from "./helpers";

const SIZE_CLASSES: Record<NonNullable<TagProps["size"]>, string> = {
  default: "comet-body-xs h-5 px-2 leading-5 rounded-sm",
  sm: "comet-body-xs h-4 px-2 text-[11px] leading-4 rounded-sm",
  md: "comet-body-s h-6 px-1.5 leading-6 rounded-md",
  lg: "comet-body-s h-7 px-3 leading-7 rounded-md",
};

type EnvironmentBadgeProps = {
  name?: string | null;
  size?: TagProps["size"];
  className?: string;
};

const EnvironmentBadge: React.FC<EnvironmentBadgeProps> = ({
  name,
  size = "sm",
  className,
}) => {
  const { data } = useEnvironmentsList();

  if (!name) return null;

  const env = data?.content?.find((e) => e.name === name);
  const bg = resolveEnvironmentColor(env?.color);
  const text = getContrastingTextColor(bg);

  return (
    <div
      className={cn(
        "inline-block max-w-[160px] truncate transition-colors",
        SIZE_CLASSES[size ?? "sm"],
        className,
      )}
      style={{ backgroundColor: bg, color: text }}
    >
      {name}
    </div>
  );
};

export default EnvironmentBadge;
