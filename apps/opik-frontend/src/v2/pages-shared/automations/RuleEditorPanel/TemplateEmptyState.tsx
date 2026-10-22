import React from "react";
import { LucideIcon, PenLine } from "lucide-react";

import { cn } from "@/lib/utils";
import { Label } from "@/ui/label";
import IconTag from "@/v2/pages-shared/IconTag/IconTag";
import { JudgeTemplateOption } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

type StartCardProps = {
  label: string;
  description: string;
  icon: LucideIcon;
  iconClassName: string;
  className?: string;
  onClick: () => void;
};

const StartCard: React.FC<StartCardProps> = ({
  label,
  description,
  icon,
  iconClassName,
  className,
  onClick,
}) => (
  <button
    type="button"
    onClick={(event) => {
      event.stopPropagation();
      onClick();
    }}
    className={cn(
      "flex flex-col items-start gap-1 rounded-md border border-border bg-background p-2 text-left hover:border-primary hover:bg-primary-foreground",
      className,
    )}
  >
    <span className="flex items-center gap-1">
      <IconTag icon={icon} className={iconClassName} />
      <span className="comet-body-xs-accented">{label}</span>
    </span>
    <span className="comet-body-xs text-muted-slate">{description}</span>
  </button>
);

type TemplateEmptyStateProps = {
  label: string;
  options: JudgeTemplateOption[];
  blankLabel: string;
  blankDescription: string;
  onSelect: (option: JudgeTemplateOption) => void;
  onStartBlank: () => void;
};

const TemplateEmptyState: React.FC<TemplateEmptyStateProps> = ({
  label,
  options,
  blankLabel,
  blankDescription,
  onSelect,
  onStartBlank,
}) => (
  <div
    className="grid cursor-default grid-cols-2 gap-2"
    data-testid="rule-editor-start-cards"
  >
    <Label className="col-span-2 -mb-1 px-0.5 pb-0.5">{label}</Label>
    <StartCard
      label={blankLabel}
      description={blankDescription}
      icon={PenLine}
      iconClassName="bg-slate-300"
      className="col-span-2"
      onClick={onStartBlank}
    />
    <span className="comet-body-xs-accented col-span-2 mt-2 px-0.5 text-muted-slate">
      Or choose a template
    </span>
    {options.map((option, index) => (
      <StartCard
        key={option.label}
        label={option.label}
        description={option.description}
        icon={option.icon}
        iconClassName={option.iconClassName}
        className={cn(
          index === options.length - 1 && index % 2 === 0 && "col-span-2",
        )}
        onClick={() => onSelect(option)}
      />
    ))}
  </div>
);

export default TemplateEmptyState;
