import React from "react";
import { cn } from "@/lib/utils";

interface OptimizationTemplateCardProps {
  name: string;
  description: string;
  icon?: React.ComponentType<{ className?: string }>;
  iconColor?: string;
  tags?: string[];
  interactive?: boolean;
  onClick?: () => void;
}

const OptimizationTemplateCard: React.FunctionComponent<
  OptimizationTemplateCardProps
> = ({
  name,
  description,
  icon: Icon,
  iconColor = "text-foreground",
  tags,
  interactive = false,
  onClick,
}) => {
  const className = cn(
    "flex h-full w-full flex-col gap-1 rounded-md border border-border bg-background p-4 text-left",
    interactive &&
      "cursor-pointer transition-colors hover:border-primary hover:bg-muted",
  );

  const content = (
    <>
      <div className="flex h-5 items-center gap-2">
        {Icon && (
          <div className="flex size-4 shrink-0 items-center justify-center">
            <Icon className={cn("size-4", iconColor)} />
          </div>
        )}
        <h4 className="comet-body-s-accented text-foreground">{name}</h4>
      </div>
      <p className="comet-body-xs text-muted-slate">{description}</p>
      {tags && tags.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {tags.map((tag) => (
            <span
              key={tag}
              className="comet-body-xs rounded bg-muted px-1.5 py-0.5 text-muted-slate"
            >
              {tag}
            </span>
          ))}
        </div>
      )}
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

export default OptimizationTemplateCard;
