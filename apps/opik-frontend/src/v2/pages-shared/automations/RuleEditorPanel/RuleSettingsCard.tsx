import React, { useState } from "react";
import { ChevronDown, LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import IconTag from "@/shared/IconTag/IconTag";

type RuleSettingsCardProps = {
  icon: LucideIcon;
  iconClassName: string;
  title: string;
  description?: string;
  tooltip?: string;
  collapsible?: boolean;
  defaultOpen?: boolean;
  children: React.ReactNode;
};

const RuleSettingsCard: React.FC<RuleSettingsCardProps> = ({
  icon: Icon,
  iconClassName,
  title,
  description,
  tooltip,
  collapsible = false,
  defaultOpen = true,
  children,
}) => {
  const [open, setOpen] = useState(defaultOpen);
  const isOpen = !collapsible || open;

  const titleRow = (
    <div className="flex w-full items-center gap-1.5">
      <IconTag icon={Icon} className={iconClassName} size="md" />
      <span className="comet-body-s-accented flex flex-1 items-center gap-1 text-left">
        <span>{title}</span>
        {tooltip && <ExplainerIcon description={tooltip} />}
      </span>
      {collapsible && (
        <ChevronDown
          className={cn(
            "size-4 shrink-0 text-muted-slate transition-transform",
            isOpen && "rotate-180",
          )}
        />
      )}
    </div>
  );

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div
        className={cn(
          "flex flex-col gap-1.5 p-3",
          isOpen && "border-b border-border",
        )}
      >
        {collapsible ? (
          <button
            type="button"
            onClick={() => setOpen(!open)}
            aria-expanded={isOpen}
          >
            {titleRow}
          </button>
        ) : (
          titleRow
        )}
        {description && <p className="comet-body-s">{description}</p>}
      </div>
      {isOpen && children}
    </div>
  );
};

export default RuleSettingsCard;
