import React from "react";
import { FileTerminal, Hash, Split, Type } from "lucide-react";
import { BlueprintValueType } from "@/types/agent-configs";

const TYPE_CONFIG: Record<
  BlueprintValueType,
  { icon: React.ComponentType<{ className?: string }>; color: string }
> = {
  [BlueprintValueType.INT]: { icon: Hash, color: "var(--color-blue)" },
  [BlueprintValueType.FLOAT]: { icon: Hash, color: "var(--color-blue)" },
  [BlueprintValueType.BOOLEAN]: {
    icon: Split,
    color: "var(--color-green)",
  },
  [BlueprintValueType.PROMPT]: {
    icon: FileTerminal,
    color: "var(--color-burgundy)",
  },
  [BlueprintValueType.STRING]: { icon: Type, color: "var(--color-violet)" },
};

const FALLBACK_TYPE_CONFIG = { icon: Type, color: "var(--color-gray)" };

const SIZE_CLASSES = {
  default: { outer: "size-5", inner: "size-3.5" },
  sm: { outer: "size-4", inner: "size-2.5" },
} as const;

const TONE_COLORS: Record<"added" | "removed", { bg: string; fg: string }> = {
  added: { bg: "var(--diff-added-text)", fg: "var(--diff-added-bg)" },
  removed: { bg: "var(--diff-removed-text)", fg: "var(--diff-removed-bg)" },
};

type BlueprintTypeIconProps = {
  type: BlueprintValueType;
  variant?: "default" | "secondary";
  size?: keyof typeof SIZE_CLASSES;
  tone?: "added" | "removed";
};

const BlueprintTypeIcon: React.FC<BlueprintTypeIconProps> = ({
  type,
  variant = "default",
  size = "default",
  tone,
}) => {
  const { icon: Icon, color } = TYPE_CONFIG[type] ?? FALLBACK_TYPE_CONFIG;
  const isSecondary = variant === "secondary";
  const bg = tone
    ? TONE_COLORS[tone].bg
    : isSecondary
      ? "hsl(var(--muted-disabled))"
      : color;
  const fg = tone
    ? TONE_COLORS[tone].fg
    : isSecondary
      ? "hsl(var(--muted-slate))"
      : "white";
  const sizeClasses = SIZE_CLASSES[size];
  return (
    <span
      className={`flex ${sizeClasses.outer} shrink-0 items-center justify-center rounded bg-[var(--icon-bg)] text-[var(--icon-color)]`}
      style={
        {
          "--icon-bg": bg,
          "--icon-color": fg,
        } as React.CSSProperties
      }
    >
      <Icon className={sizeClasses.inner} />
    </span>
  );
};

export default BlueprintTypeIcon;
