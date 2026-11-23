import React from "react";
import { PenLine, Plus } from "lucide-react";

import { cn } from "@/lib/utils";
import IconTag from "@/shared/IconTag/IconTag";
import { JudgeTemplateOption } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

type TemplateCardProps = {
  label: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  iconClassName: string;
  isSelected?: boolean;
  isDashed?: boolean;
  onClick: () => void;
};

const TemplateCard: React.FC<TemplateCardProps> = ({
  label,
  description,
  icon,
  iconClassName,
  isSelected = false,
  isDashed = false,
  onClick,
}) => (
  <button
    type="button"
    aria-pressed={isSelected}
    onClick={onClick}
    className={cn(
      "flex flex-col items-start gap-1 rounded-lg border p-2 text-left transition-colors hover:border-primary focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring",
      isDashed
        ? "border-dashed border-slate-300 bg-soft-background"
        : "border-border bg-background shadow-sm",
      isSelected && "border-primary bg-primary-foreground",
    )}
  >
    <span className="flex flex-col items-start gap-2">
      <IconTag icon={icon} className={iconClassName} />
      <span className="comet-body-xs-accented text-foreground">{label}</span>
    </span>
    <span className="comet-body-xs text-muted-slate">{description}</span>
  </button>
);

type TemplateGridProps = {
  options: JudgeTemplateOption[];
  selectedOption?: JudgeTemplateOption;
  showCustom: boolean;
  isCustomSelected: boolean;
  customDescription: string;
  blankDescription: string;
  onSelect: (option: JudgeTemplateOption) => void;
  onSelectCustom: () => void;
  onStartBlank: () => void;
};

const TemplateGrid: React.FC<TemplateGridProps> = ({
  options,
  selectedOption,
  showCustom,
  isCustomSelected,
  customDescription,
  blankDescription,
  onSelect,
  onSelectCustom,
  onStartBlank,
}) => (
  <div
    className="flex flex-col gap-2 overflow-y-auto p-3"
    data-testid="rule-editor-start-cards"
  >
    <span className="comet-body-s px-0.5 text-foreground">
      Choose a template
    </span>
    <div className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-2">
      {showCustom && (
        <TemplateCard
          label="Custom"
          description={customDescription}
          icon={PenLine}
          iconClassName="bg-slate-300"
          isSelected={isCustomSelected}
          onClick={onSelectCustom}
        />
      )}
      {options.map((option) => (
        <TemplateCard
          key={option.label}
          label={option.label}
          description={option.description}
          icon={option.icon}
          iconClassName={option.iconClassName}
          isSelected={option === selectedOption}
          onClick={() => onSelect(option)}
        />
      ))}
      <TemplateCard
        label="Start blank"
        description={blankDescription}
        icon={Plus}
        iconClassName="bg-slate-200"
        isDashed
        onClick={onStartBlank}
      />
    </div>
  </div>
);

export default TemplateGrid;
