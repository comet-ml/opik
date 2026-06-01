import React from "react";
import { TagProps } from "@/ui/tag";
import { cn } from "@/lib/utils";
import {
  getContrastingTextColor,
  getDefaultEnvironmentMeta,
  resolveEnvironmentColor,
} from "./helpers";
import { useEnvironmentByName } from "./EnvironmentLabel";

// `pill` is a project-specific variant used across prompt-version
// surfaces (prompt detail header, mobile version picker, version history
// timeline, +N overflow tooltip): 20px tall with a 10px label and asymmetric
// padding so the leading env icon hugs the left edge while the text gets a
// little more breathing room on the right.
export type EnvironmentBadgeSize = NonNullable<TagProps["size"]> | "pill";

const SIZE_CLASSES: Record<EnvironmentBadgeSize, string> = {
  default: "comet-body-xs h-5 px-2 leading-5 rounded-sm",
  sm: "comet-body-xs h-4 px-2 text-[11px] leading-4 rounded-sm",
  md: "comet-body-s h-6 px-1.5 leading-6 rounded-md",
  lg: "comet-body-s h-7 px-3 leading-7 rounded-md",
  pill: "h-5 pl-1.5 pr-2 text-[10px] leading-5 rounded-sm",
};

const ICON_SIZE_CLASSES: Record<EnvironmentBadgeSize, string> = {
  default: "size-3",
  sm: "size-2.5",
  md: "size-3",
  lg: "size-3.5",
  pill: "size-2.5",
};

type EnvironmentBadgeProps = {
  name?: string | null;
  size?: EnvironmentBadgeSize;
  className?: string;
};

const EnvironmentBadge: React.FC<EnvironmentBadgeProps> = ({
  name,
  size = "sm",
  className,
}) => {
  const env = useEnvironmentByName(name);

  if (!name) return null;

  const bg = resolveEnvironmentColor(env?.color);
  const text = getContrastingTextColor(bg);
  const defaultMeta = getDefaultEnvironmentMeta(name);
  const Icon = defaultMeta?.icon;
  const resolvedSize = size ?? "sm";

  return (
    <div
      className={cn(
        "inline-flex max-w-[160px] items-center gap-1 truncate transition-colors",
        SIZE_CLASSES[resolvedSize],
        className,
      )}
      style={{ backgroundColor: bg, color: text }}
    >
      {Icon && (
        <Icon
          className={cn("shrink-0 text-white", ICON_SIZE_CLASSES[resolvedSize])}
        />
      )}
      <span className="truncate">{name}</span>
    </div>
  );
};

export default EnvironmentBadge;
