import React from "react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface DashboardTemplateCardProps {
  name: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  iconColor: string;
  interactive?: boolean;
  disabled?: boolean;
  disabledTooltip?: string;
  onClick?: () => void;
}

const DashboardTemplateCard: React.FunctionComponent<
  DashboardTemplateCardProps
> = ({
  name,
  description,
  icon: Icon,
  iconColor,
  interactive = false,
  disabled = false,
  disabledTooltip,
  onClick,
}) => {
  const className =
    "flex flex-col gap-1 rounded-md border border-border bg-background p-4 text-left";

  const content = (
    <>
      <div className="flex h-5 items-center gap-2">
        <div className="flex size-4 shrink-0 items-center justify-center">
          <Icon className={iconColor} />
        </div>
        <h4 className="comet-body-s-accented text-foreground">{name}</h4>
      </div>
      <p className="comet-body-xs text-muted-slate">{description}</p>
    </>
  );

  if (disabled) {
    return (
      <TooltipWrapper
        content={
          disabledTooltip ?? "You don't have permission to view this template."
        }
      >
        <div className={cn(className, "cursor-not-allowed opacity-50")}>
          {content}
        </div>
      </TooltipWrapper>
    );
  }

  if (interactive) {
    return (
      <button
        type="button"
        onClick={() => onClick?.()}
        disabled={disabled}
        className={cn(
          className,
          "cursor-pointer transition-colors hover:border-primary hover:bg-muted",
        )}
      >
        {content}
      </button>
    );
  }

  return <div className={className}>{content}</div>;
};

export default DashboardTemplateCard;
