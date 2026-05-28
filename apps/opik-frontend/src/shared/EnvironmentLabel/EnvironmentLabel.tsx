import React from "react";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import { cn } from "@/lib/utils";
import { resolveEnvironmentColor } from "./helpers";

export const EnvironmentSquare: React.FC<{
  color?: string;
  className?: string;
}> = ({ color, className }) => (
  <div
    className={cn("size-2.5 shrink-0 rounded-[0.15rem]", className)}
    style={{ backgroundColor: resolveEnvironmentColor(color) }}
  />
);

type EnvironmentLabelProps = {
  name?: string;
  className?: string;
};

const EnvironmentLabel: React.FC<EnvironmentLabelProps> = ({
  name,
  className,
}) => {
  const { data } = useEnvironmentsList();

  if (!name) {
    return <div className={cn("text-light-slate", className)}>-</div>;
  }

  const env = data?.content?.find((e) => e.name === name);

  return (
    <div className={cn("flex min-w-0 items-center gap-1.5", className)}>
      <EnvironmentSquare color={env?.color} />
      <span className="truncate">{name}</span>
    </div>
  );
};

export default EnvironmentLabel;
