import React from "react";
import { FileTerminal, Hash, ToggleLeft, Type } from "lucide-react";

const TYPE_CONFIG: Record<
  string,
  { icon: React.ComponentType<{ className?: string }>; color: string }
> = {
  int: { icon: Hash, color: "var(--color-blue)" },
  float: { icon: Hash, color: "var(--color-blue)" },
  boolean: { icon: ToggleLeft, color: "var(--color-green)" },
  Prompt: { icon: FileTerminal, color: "var(--color-burgundy)" },
  string: { icon: Type, color: "var(--color-violet)" },
};

const FALLBACK_TYPE_CONFIG = { icon: Type, color: "var(--color-gray)" };

type BlueprintTypeIconProps = {
  type: string;
  variant?: "default" | "secondary";
};

const BlueprintTypeIcon: React.FC<BlueprintTypeIconProps> = ({
  type,
  variant = "default",
}) => {
  const { icon: Icon, color } = TYPE_CONFIG[type] ?? FALLBACK_TYPE_CONFIG;
  const isSecondary = variant === "secondary";
  return (
    <span
      className="flex size-5 shrink-0 items-center justify-center rounded bg-[var(--icon-bg)] text-[var(--icon-color)]"
      style={
        {
          "--icon-bg": isSecondary ? "hsl(var(--muted-disabled))" : color,
          "--icon-color": isSecondary ? "hsl(var(--muted-slate))" : "white",
        } as React.CSSProperties
      }
    >
      <Icon className="size-3.5" />
    </span>
  );
};

export default BlueprintTypeIcon;
