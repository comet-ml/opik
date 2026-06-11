import React from "react";
import { cn } from "@/lib/utils";

interface UserMetricProps {
  value: string;
  label: string;
  className?: string;
}

const UserMetric: React.FC<UserMetricProps> = ({ value, label, className }) => (
  <div className={cn("flex shrink-0 flex-col items-start", className)}>
    <span className="comet-body-xs-accented leading-4 text-foreground">
      {value}
    </span>
    <span className="text-[10px] leading-[14px] text-light-slate">{label}</span>
  </div>
);

export default UserMetric;
