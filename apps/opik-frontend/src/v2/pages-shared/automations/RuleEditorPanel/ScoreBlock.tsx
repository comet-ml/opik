import React from "react";
import { BrainCircuit, LucideIcon, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import IconTag from "@/shared/IconTag/IconTag";

type ScoreBlockProps = {
  title: string;
  heading?: React.ReactNode;
  icon?: LucideIcon;
  iconClassName?: string;
  onRemove?: () => void;
  children: React.ReactNode;
};

const ScoreBlock: React.FC<ScoreBlockProps> = ({
  title,
  heading,
  icon = BrainCircuit,
  iconClassName = "bg-[var(--rule-icon-llm-judge)]",
  onRemove,
  children,
}) => (
  <div className="overflow-hidden rounded-md border border-border bg-soft-background">
    <div className="flex h-8 items-center gap-1.5 pl-2 pr-3">
      <IconTag icon={icon} className={iconClassName} />
      {heading ?? (
        <span className="comet-body-xs-accented flex-1 text-muted-slate">
          {title}
        </span>
      )}
      {onRemove && (
        <Button
          type="button"
          variant="minimal"
          size="icon-3xs"
          onClick={onRemove}
          aria-label={`Remove ${title}`}
        >
          <Trash />
        </Button>
      )}
    </div>
    <div className="flex flex-col gap-1.5 px-1.5 pb-1.5">{children}</div>
  </div>
);

export default ScoreBlock;
