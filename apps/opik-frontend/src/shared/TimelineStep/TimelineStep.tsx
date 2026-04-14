import React from "react";
import { Check, LoaderCircle } from "lucide-react";

type TimelineStepProps = {
  number?: number;
  isLast?: boolean;
  completed?: boolean;
  children: React.ReactNode;
};

const TimelineStep: React.FC<TimelineStepProps> = ({
  number,
  isLast,
  completed,
  children,
}) => (
  <div className="flex gap-3">
    <div className="flex flex-col items-center">
      {number != null ? (
        <div className="flex size-4 shrink-0 items-center justify-center rounded-full border border-[var(--timeline-connector)] text-[8px] font-semibold text-[var(--timeline-connector)]">
          {number}
        </div>
      ) : completed ? (
        <div className="flex size-4 shrink-0 items-center justify-center rounded-full bg-primary">
          <Check className="size-2.5 text-primary-foreground" />
        </div>
      ) : (
        <div className="relative flex size-4 shrink-0 items-center justify-center">
          <div className="absolute inset-0 animate-ping rounded-full bg-primary/20" />
          <LoaderCircle className="relative size-3.5 animate-spin text-primary" />
        </div>
      )}
      {!isLast && (
        <div className="w-px flex-1 bg-[var(--timeline-connector)] opacity-50" />
      )}
    </div>
    <div className="flex-1 pb-6">{children}</div>
  </div>
);

export default TimelineStep;
