import React from "react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export interface CardOption {
  value: string;
  label: string;
  description?: string;
  icon?: React.ReactNode;
  iconColor?: string;
  disabled?: boolean;
  disabledTooltip?: string;
}

interface CardSelectorProps {
  value: string;
  onChange: (value: string) => void;
  options: CardOption[];
  className?: string;
}

const CardSelector: React.FC<CardSelectorProps> = ({
  value,
  onChange,
  options,
  className,
}) => {
  return (
    <div className={cn("flex flex-wrap gap-4", className)}>
      {options.map((option) => {
        const isSelected = value === option.value;
        const isCompact = !option.description;

        const card = (
          <button
            key={option.value}
            type="button"
            disabled={option.disabled}
            onClick={() => !option.disabled && onChange(option.value)}
            className={cn(
              "flex flex-1 rounded-md border text-left transition-colors",
              isCompact ? "h-12 items-center gap-2 px-4" : "flex-col gap-1 p-4",
              isSelected
                ? "border-primary bg-muted"
                : "border-border bg-background hover:border-primary hover:bg-muted",
              option.disabled && "cursor-not-allowed opacity-50",
            )}
          >
            <div className="flex items-center gap-2">
              {option.icon && (
                <div
                  className={cn(
                    "flex size-4 shrink-0 items-center justify-center",
                    option.iconColor,
                  )}
                >
                  {option.icon}
                </div>
              )}
              <span className="comet-body-s-accented text-foreground">
                {option.label}
              </span>
            </div>
            {option.description && (
              <p className="comet-body-xs text-muted-slate">
                {option.description}
              </p>
            )}
          </button>
        );

        if (option.disabled && option.disabledTooltip) {
          return (
            <TooltipWrapper key={option.value} content={option.disabledTooltip}>
              {card}
            </TooltipWrapper>
          );
        }

        return card;
      })}
    </div>
  );
};

export default CardSelector;
