import React from "react";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { DEFAULT_HEX_COLOR, HEX_COLOR_REGEX } from "@/constants/colorVariants";
import { TagProps } from "@/ui/tag";
import { cn } from "@/lib/utils";

const resolveColor = (color: string | undefined) =>
  color && HEX_COLOR_REGEX.test(color) ? color : DEFAULT_HEX_COLOR;

const getContrastingTextColor = (hex: string): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  const luminance = (r * 299 + g * 587 + b * 114) / 1000;
  return luminance > 140 ? "#0f172a" : "#ffffff";
};

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
  const bg = resolveColor(env?.color);
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
