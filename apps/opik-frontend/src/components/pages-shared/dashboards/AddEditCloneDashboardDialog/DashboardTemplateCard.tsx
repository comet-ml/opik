import React from "react";
import { cn } from "@/lib/utils";

interface DashboardTemplateCardProps {
  name: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  iconColor: string;
  interactive?: boolean;
  onClick?: () => void;
}

const DashboardTemplateCard: React.FunctionComponent<
  DashboardTemplateCardProps
> = ({
  name,
  description,
  icon: Icon,
  iconColor,
  interactive = false,
  onClick,
}) => {
  const className = cn(
    "flex flex-col gap-1 rounded-md border border-border bg-background p-4 text-left",
    interactive &&
      "cursor-pointer transition-colors hover:border-primary hover:bg-muted",
  );

  const content = (
    <>
      <div className="flex h-5 items-center gap-2">
        <div className="flex size-4 shrink-0 items-center justify-center">
          <Icon className={iconColor} />
        </div>
        <h4 className="comet-body-s-accented text-foreground">{name}</h4>
      </div>
      <p className="comet-body-xs text-muted-slate">{description}</p>
    </>
  );

  if (interactive) {
    return (
      <button type="button" onClick={onClick} className={className}>
        {content}
      </button>
    );
  }

  return <div className={className}>{content}</div>;
};

export default DashboardTemplateCard;
