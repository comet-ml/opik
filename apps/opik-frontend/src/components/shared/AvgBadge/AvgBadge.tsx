import React from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const getTrialValuesLabel = (
  values: (number | undefined)[],
  formatter: (v: number) => string,
): string => {
  const formatted = values
    .slice(0, 3)
    .map((v) => (v != null ? formatter(v) : "-"));
  const suffix = values.length > 3 ? ", …" : "";
  return `Average across ${values.length} trials: (${formatted.join(
    ", ",
  )}${suffix})`;
};

const AvgBadge: React.FC<{
  values: (number | undefined)[];
  formatter?: (v: number) => string;
}> = ({ values, formatter = (v) => v.toFixed(2) }) => {
  if (values.every((v) => v == null)) return null;

  return (
    <TooltipWrapper content={getTrialValuesLabel(values, formatter)}>
      <span className="text-[10px] leading-none text-muted-foreground">
        avg
      </span>
    </TooltipWrapper>
  );
};

export default AvgBadge;
