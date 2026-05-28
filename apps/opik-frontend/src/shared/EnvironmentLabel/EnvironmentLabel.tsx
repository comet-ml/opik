import React from "react";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { getDefaultEnvironmentMeta, resolveEnvironmentColor } from "./helpers";

/**
 * Looks up the environment definition (color, etc.) by display name from
 * the workspace-level environments list. Shared by all components that need
 * to render an environment given only its name.
 */
export const useEnvironmentByName = (name?: string | null) => {
  const { data } = useEnvironmentsList();
  if (!name) return undefined;
  return data?.content?.find((e) => e.name === name);
};

export const EnvironmentSquare: React.FC<{
  name?: string;
  color?: string;
  className?: string;
}> = ({ name, color, className }) => {
  const Icon = getDefaultEnvironmentMeta(name)?.icon;
  const resolvedColor = resolveEnvironmentColor(color);

  return (
    <div
      className={cn(
        "flex size-4 shrink-0 items-center justify-center rounded-[0.2rem]",
        className,
      )}
      style={{ backgroundColor: resolvedColor }}
    >
      {Icon && <Icon className="size-2.5 text-white" />}
    </div>
  );
};

/**
 * Name-driven variant of EnvironmentSquare: resolves color by name and wraps
 * the square in a tooltip that reveals the environment name on hover. Used in
 * tight layouts where the name doesn't fit inline.
 */
export const EnvironmentSquareWithTooltip: React.FC<{ name: string }> = ({
  name,
}) => {
  const env = useEnvironmentByName(name);
  return (
    <TooltipWrapper content={name}>
      <span className="inline-flex">
        <EnvironmentSquare name={name} color={env?.color} />
      </span>
    </TooltipWrapper>
  );
};

type EnvironmentLabelProps = {
  name?: string;
  className?: string;
};

const EnvironmentLabel: React.FC<EnvironmentLabelProps> = ({
  name,
  className,
}) => {
  const env = useEnvironmentByName(name);

  if (!name) {
    return <div className={cn("text-light-slate", className)}>-</div>;
  }

  return (
    <div className={cn("flex min-w-0 items-center gap-1.5", className)}>
      <EnvironmentSquare name={name} color={env?.color} />
      <span className="truncate">{name}</span>
    </div>
  );
};

export default EnvironmentLabel;
