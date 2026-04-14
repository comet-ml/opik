import React from "react";
import { Info } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type FieldSectionProps = {
  label: React.ReactNode;
  icon?: React.ReactNode;
  description?: string;
  trailing?: React.ReactNode;
  children: React.ReactNode;
  testId?: string;
};

const FieldSection: React.FC<FieldSectionProps> = ({
  label,
  icon,
  description,
  trailing,
  children,
  testId,
}) => {
  return (
    <div className="flex flex-col gap-2" data-testid={testId}>
      <div className="flex items-center gap-1.5">
        {icon}
        <span className="comet-body-s-accented truncate text-foreground">
          {label}
        </span>
        {description && (
          <TooltipWrapper content={description}>
            <Info className="size-3 shrink-0 cursor-help text-light-slate" />
          </TooltipWrapper>
        )}
        {trailing && (
          <div className="ml-auto flex items-center gap-1">{trailing}</div>
        )}
      </div>
      <div className="flex flex-col gap-2">{children}</div>
    </div>
  );
};

export default FieldSection;
