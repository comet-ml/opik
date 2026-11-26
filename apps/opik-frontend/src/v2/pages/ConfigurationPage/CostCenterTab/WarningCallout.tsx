import React from "react";
import { TriangleAlert } from "lucide-react";
import { cn } from "@/lib/utils";

const WarningCallout: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({
  className,
  children,
  ...props
}) => (
  <div
    className={cn(
      "comet-body-xs flex items-start gap-2 rounded-md bg-warning-box-bg p-2 text-warning-box-text",
      className,
    )}
    {...props}
  >
    <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-warning-box-icon-text" />
    <div className="min-w-0 flex-1 space-y-1">{children}</div>
  </div>
);

export default WarningCallout;
