import React from "react";
import { FileTerminal, Hash, ToggleLeft, Type } from "lucide-react";
import { BlueprintValueType } from "@/types/agent-configs";

const TYPE_CONFIG: Record<
  BlueprintValueType,
  { icon: React.ComponentType<{ className?: string }>; color: string }
> = {
  [BlueprintValueType.INT]: { icon: Hash, color: "var(--color-blue)" },
  [BlueprintValueType.FLOAT]: { icon: Hash, color: "var(--color-blue)" },
  [BlueprintValueType.BOOLEAN]: {
    icon: ToggleLeft,
    color: "var(--color-green)",
  },
  [BlueprintValueType.PROMPT]: {
    icon: FileTerminal,
    color: "var(--color-burgundy)",
  },
  [BlueprintValueType.STRING]: { icon: Type, color: "var(--color-violet)" },
};

const FALLBACK_TYPE_CONFIG = { icon: Type, color: "var(--color-gray)" };

type BlueprintTypeIconProps = {
  type: BlueprintValueType;
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
