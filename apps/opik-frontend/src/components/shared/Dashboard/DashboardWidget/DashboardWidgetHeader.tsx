import React, { useState } from "react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DashboardWidgetHeaderProps = {
  title: string;
  subtitle?: string;
  messages?: (string | React.ReactNode)[];
  actions?: React.ReactElement<{ onOpenChange?: (open: boolean) => void }>;
  dragHandle?: React.ReactNode;
  className?: string;
  preview?: boolean;
};

const DashboardWidgetHeader: React.FunctionComponent<
  DashboardWidgetHeaderProps
> = ({
  title,
  subtitle,
  messages,
  actions,
  dragHandle,
  className,
  preview = false,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);

  const actionsWithHandler =
    actions && !preview
      ? React.cloneElement(actions, { onOpenChange: setMenuOpen })
      : null;

  const showDragHandle = dragHandle && !preview;

  const renderMessages = () => {
    if (!messages || messages.length === 0) return null;

    return messages.map((msg, index) => (
      <React.Fragment key={index}>
        {index > 0 && <span className="mx-1">·</span>}
        {msg}
      </React.Fragment>
    ));
  };

  return (
    <div
      className={cn(
        "relative flex flex-col gap-0.5 rounded px-2 pb-0.5",
        showDragHandle ? "pt-1" : "pt-2",
        className,
      )}
    >
      {showDragHandle && (
        <div
          className={cn(
            "flex w-full items-center justify-center pb-0.5 transition-opacity",
            menuOpen
              ? "opacity-100"
              : "opacity-0 group-hover/widget:opacity-100",
          )}
        >
          {dragHandle}
        </div>
      )}
      <div className="flex items-start gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="truncate text-xs font-medium text-foreground">
            {title}
          </div>
          {subtitle && (
            <TooltipWrapper content={subtitle}>
              <div className="line-clamp-3 text-xs font-normal text-muted-slate">
                {subtitle}
              </div>
            </TooltipWrapper>
          )}
          {messages && messages.length > 0 && (
            <TooltipWrapper content={<div>{renderMessages()}</div>}>
              <div className="line-clamp-2 text-xs font-normal text-muted-slate">
                {renderMessages()}
              </div>
            </TooltipWrapper>
          )}
        </div>
        {actionsWithHandler && (
          <div
            className={cn(
              "flex shrink-0 items-center gap-2 transition-opacity",
              menuOpen
                ? "opacity-100"
                : "opacity-0 group-hover/widget:opacity-100",
            )}
          >
            {actionsWithHandler}
          </div>
        )}
      </div>
    </div>
  );
};

export default DashboardWidgetHeader;
