import React from "react";
import { formatExactCost } from "@/lib/money";
import { cn } from "@/lib/utils";

type CostCardProps = {
  total: number;
  subtitle?: string;
  children?: React.ReactNode;
};

const CostCard: React.FC<CostCardProps> = ({ total, subtitle, children }) => (
  <div className="comet-custom-scrollbar flex min-h-0 flex-col gap-3 overflow-y-auto p-4">
    <div className="flex flex-col gap-0.5">
      <div className="flex items-baseline justify-between gap-4">
        <span className="comet-body-s-accented">Estimated cost</span>
        <span className="comet-body-s-accented tabular-nums">
          {formatExactCost(total)}
        </span>
      </div>
      {subtitle && (
        <span className="comet-body-xs truncate text-muted-slate">
          {subtitle}
        </span>
      )}
    </div>
    {children}
  </div>
);

type CostNoteProps = {
  children: React.ReactNode;
  variant?: "default" | "warning";
};

export const CostNote: React.FC<CostNoteProps> = ({
  children,
  variant = "default",
}) => (
  <p
    className={cn(
      "comet-body-xs rounded-md px-2 py-1.5",
      variant === "warning"
        ? "bg-warning-box-bg text-warning-box-text"
        : "bg-muted text-muted-slate",
    )}
  >
    {children}
  </p>
);

export default CostCard;
