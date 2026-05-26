import React from "react";

import { cn } from "@/lib/utils";

type FormFieldCardProps = {
  title: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
  bodyClassName?: string;
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
  children,
}) => (
  <div
    className={cn(
      "overflow-hidden rounded-md border border-border bg-soft-background pb-2",
      className,
    )}
  >
    <div className="flex h-8 items-center gap-2 border-b border-border px-2">
      <span className="comet-body-xs-accented flex-1 truncate text-muted-slate">
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
