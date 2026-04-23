import React from "react";
import { ChevronDown, ChevronRight, Info } from "lucide-react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type FieldSectionProps = {
  label: React.ReactNode;
  icon?: React.ReactNode;
  description?: string;
  afterLabel?: React.ReactNode;
  trailing?: React.ReactNode;
  children: React.ReactNode;
  testId?: string;
  expandable?: boolean;
  expanded?: boolean;
  onToggle?: () => void;
};

const FieldSection: React.FC<FieldSectionProps> = ({
  label,
  icon,
  description,
  afterLabel,
  trailing,
  children,
  testId,
  expandable,
  expanded,
  onToggle,
}) => {
  const isClickable = !!expandable && !!onToggle;
  const handleToggle = () => onToggle?.();
  return (
    <div className="flex flex-col gap-2" data-testid={testId}>
      <div
        className={cn(
          "flex items-center gap-1.5",
          isClickable && "cursor-pointer select-none",
        )}
        onClick={isClickable ? handleToggle : undefined}
        role={isClickable ? "button" : undefined}
        tabIndex={isClickable ? 0 : undefined}
        aria-expanded={expandable ? !!expanded : undefined}
        onKeyDown={
          isClickable
            ? (e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  handleToggle();
                }
              }
            : undefined
        }
      >
        <span
          aria-hidden={!expandable}
          className="flex size-3.5 shrink-0 items-center justify-center text-light-slate"
        >
          {expandable &&
            (expanded ? (
              <ChevronDown className="size-3.5" />
            ) : (
              <ChevronRight className="size-3.5" />
            ))}
        </span>
        {icon}
        <span className="comet-body-s-accented truncate text-foreground">
          {label}
        </span>
        {description && (
          <TooltipWrapper content={description}>
            <Info className="size-3 shrink-0 cursor-help text-light-slate" />
          </TooltipWrapper>
        )}
        {afterLabel && (
          <div
            className="flex items-center gap-1"
            onClick={(e) => e.stopPropagation()}
          >
            {afterLabel}
          </div>
        )}
        {trailing && (
          <div
            className="ml-auto flex items-center gap-1"
            onClick={(e) => e.stopPropagation()}
          >
            {trailing}
          </div>
        )}
      </div>
      <div className="flex flex-col gap-2 pl-5">{children}</div>
    </div>
  );
};

export default FieldSection;
