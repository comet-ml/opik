import React from "react";
import { LayoutTemplate } from "lucide-react";

import { cn } from "@/lib/utils";
import IconTag from "@/shared/IconTag/IconTag";
import RuleEditorCard from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorCard";
import TemplateGrid from "@/v2/pages-shared/automations/RuleEditorPanel/TemplateGrid";
import { RuleTemplatePicker } from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleTemplatePicker";

type RuleContentTabProps = {
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  iconClassName: string;
  isSelected: boolean;
  onClick: () => void;
};

const RuleContentTab: React.FC<RuleContentTabProps> = ({
  label,
  icon,
  iconClassName,
  isSelected,
  onClick,
}) => (
  <button
    type="button"
    role="tab"
    aria-selected={isSelected}
    onClick={onClick}
    className={cn(
      "comet-body-xs flex h-6 items-center gap-1.5",
      isSelected ? "text-foreground" : "text-muted-slate hover:text-foreground",
    )}
  >
    <IconTag
      icon={icon}
      className={isSelected ? iconClassName : "bg-slate-200"}
      iconClassName={isSelected ? undefined : "text-slate-700"}
    />
    {label}
  </button>
);

type RuleContentCardProps = {
  picker: RuleTemplatePicker;
  contentLabel: string;
  contentIcon: React.ComponentType<{ className?: string }>;
  contentIconClassName: string;
  blankDescription: string;
  headerEnd?: React.ReactNode;
  hasError?: boolean;
  className?: string;
  children?: React.ReactNode;
};

const RuleContentCard: React.FC<RuleContentCardProps> = ({
  picker,
  contentLabel,
  contentIcon,
  contentIconClassName,
  blankDescription,
  headerEnd,
  hasError,
  className,
  children,
}) => (
  <RuleEditorCard
    hasError={hasError}
    className={className}
    header={
      <>
        <div role="tablist" className="flex items-center gap-3.5">
          <RuleContentTab
            label="Templates"
            icon={LayoutTemplate}
            iconClassName="bg-violet-300"
            isSelected={picker.showTemplates}
            onClick={picker.openTemplates}
          />
          <RuleContentTab
            label={contentLabel}
            icon={contentIcon}
            iconClassName={contentIconClassName}
            isSelected={!picker.showTemplates}
            onClick={picker.openContent}
          />
        </div>
        <div className="flex-1" />
        {headerEnd}
      </>
    }
  >
    {picker.showTemplates ? (
      <TemplateGrid {...picker.gridProps} blankDescription={blankDescription} />
    ) : (
      children
    )}
  </RuleEditorCard>
);

export default RuleContentCard;
