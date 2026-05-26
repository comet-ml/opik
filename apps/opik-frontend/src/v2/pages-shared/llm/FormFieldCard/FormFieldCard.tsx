import React from "react";

import { cn } from "@/lib/utils";

type FormFieldCardProps = {
  title: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
  bodyClassName?: string;
  /**
   * When false, the header omits its bottom border. Use this when the body
   * renders items that already have their own border (e.g. message cards),
   * to avoid a double divider between header and first item.
   */
  headerBordered?: boolean;
  children: React.ReactNode;
};

/**
 * Editable equivalent of the trace-side `CodeBlock` card. Same outer border,
 * header bar, and body padding so the prompt-form fields visually match the
 * trace details panel.
 */
const FormFieldCard: React.FC<FormFieldCardProps> = ({
  title,
  actions,
  className,
  bodyClassName,
  headerBordered = true,
  children,
}) => (
  <div
    className={cn(
      "overflow-hidden rounded-md border border-border bg-soft-background pb-2",
      className,
    )}
  >
    <div
      className={cn(
        "flex h-8 items-center gap-2 px-2",
        headerBordered && "border-b border-border",
      )}
    >
      <span className="comet-body-xs flex-1 truncate text-muted-slate">
        {title}
      </span>
      {actions && (
        <div className="flex shrink-0 items-center gap-2">{actions}</div>
      )}
    </div>
    <div className={cn("px-2 pt-2", bodyClassName)}>{children}</div>
  </div>
);

export default FormFieldCard;
