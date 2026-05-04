import React, { useState } from "react";
import { ChevronRight } from "lucide-react";

import { cn } from "@/lib/utils";

type CollapsibleSectionProps = {
  title: React.ReactNode;
  actions?: React.ReactNode;
  defaultOpen?: boolean;
  disabled?: boolean;
  forceMount?: boolean;
  className?: string;
  bodyClassName?: string;
  children: React.ReactNode;
};

const CollapsibleSection: React.FC<CollapsibleSectionProps> = ({
  title,
  actions,
  defaultOpen = true,
  disabled = false,
  forceMount = true,
  className,
  bodyClassName,
  children,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  const handleToggle = () => {
    if (disabled) return;
    setIsOpen((prev) => !prev);
  };

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border border-border bg-soft-background",
        className,
      )}
    >
      <div
        className={cn(
          "flex h-8 w-full items-center px-2",
          isOpen && "border-b border-border",
        )}
      >
        <button
          type="button"
          aria-expanded={isOpen}
          aria-disabled={disabled ? true : undefined}
          onClick={handleToggle}
          className={cn(
            "flex h-full min-w-0 flex-1 items-center gap-1 text-left transition-colors",
            disabled
              ? "cursor-not-allowed opacity-60"
              : "cursor-pointer hover:opacity-80",
          )}
        >
          <ChevronRight
            className={cn(
              "size-3.5 shrink-0 text-light-slate transition-transform duration-200",
              isOpen && "rotate-90",
            )}
          />
          <span className="comet-body-xs-accented truncate text-muted-slate">
            {title}
          </span>
        </button>
        {actions && (
          <div className="flex shrink-0 items-center gap-2">{actions}</div>
        )}
      </div>
      {forceMount ? (
        <div className={cn(!isOpen && "hidden", bodyClassName)}>{children}</div>
      ) : (
        isOpen && <div className={bodyClassName}>{children}</div>
      )}
    </div>
  );
};

export default CollapsibleSection;
