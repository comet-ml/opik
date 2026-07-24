import React from "react";
import { CornerDownRight } from "lucide-react";
import { IssuesIllustration } from "./illustrations";

import GaugeHigh from "@/icons/gauge-high.svg?react";
import GaugeMed from "@/icons/gauge-medium.svg?react";
import GaugeLow from "@/icons/gauge-low.svg?react";

interface ImpactCardProps {
  level: "High" | "Medium" | "Low";
  title: string;
  boldPart: string;
}

const BADGE_CONFIG: Record<
  ImpactCardProps["level"],
  {
    bgClass: string;
    textClass: string;
    Icon: React.FC<React.SVGProps<SVGSVGElement>>;
  }
> = {
  High: {
    bgClass: "bg-red-500",
    textClass: "text-white",
    Icon: GaugeHigh,
  },
  Medium: {
    bgClass: "bg-amber-400",
    textClass: "text-slate-900",
    Icon: GaugeMed,
  },
  Low: {
    bgClass: "bg-sky-300",
    textClass: "text-slate-900",
    Icon: GaugeLow,
  },
};

const ImpactCard: React.FC<ImpactCardProps> = ({ level, title, boldPart }) => {
  const { Icon, bgClass, textClass } = BADGE_CONFIG[level];

  return (
    <div className="flex flex-col rounded-md border border-border bg-soft-background p-3 dark:bg-accent-background">
      <div className="pb-1.5">
        <span
          className={`inline-flex h-5 items-center gap-1 rounded pb-px pl-1 pr-1.5 text-[10px] font-medium ${bgClass} ${textClass}`}
        >
          <Icon className="size-3" />
          {level} impact
        </span>
      </div>
      <p className="text-xs text-foreground">{title}</p>
      <div className="flex items-center gap-1 rounded py-px pl-0.5">
        <CornerDownRight className="size-2.5 shrink-0 text-light-slate" />
        <p className="text-xs leading-[14px] text-muted-slate">
          Opik suggests <span className="font-medium">{boldPart}</span>
        </p>
      </div>
    </div>
  );
};

const IssuesStep: React.FC = () => (
  <>
    <div className="slide-fade-right">
      <IssuesIllustration />
    </div>

    <div className="flex flex-col gap-1.5 px-0.5">
      <h1 className="slide-fade-right text-lg font-medium text-foreground [animation-delay:75ms]">
        We find issues and suggest fixes
      </h1>
      <p className="slide-fade-right pb-2 text-sm text-muted-slate [animation-delay:150ms]">
        Opik detects retries, cache misses, and other issues, then recommends
        concrete fixes.
      </p>
    </div>

    <div className="flex flex-col gap-2">
      <div className="slide-fade-right [animation-delay:225ms]">
        <ImpactCard
          level="High"
          title="Slow knowledge search"
          boldPart="caching common searches"
        />
      </div>
      <div className="slide-fade-right [animation-delay:325ms]">
        <ImpactCard
          level="Medium"
          title="Large answer generation"
          boldPart="caching the system prompt"
        />
      </div>
      <div className="slide-fade-right [animation-delay:425ms]">
        <ImpactCard
          level="Low"
          title="Too many docs retrieved"
          boldPart="limiting retrieved pages"
        />
      </div>
    </div>
  </>
);

export default IssuesStep;
