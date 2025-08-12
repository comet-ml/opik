import React from "react";
import { cn } from "@/lib/utils";
import { Tag } from "@/components/ui/tag";

type IntegrationCardProps = {
  title: string;
  description?: string;
  tag?: string;
  icon: React.ReactNode;
  className?: string;
  onClick?: () => void;
};

const IntegrationCard: React.FC<IntegrationCardProps> = ({
  title,
  description,
  tag,
  icon,
  className,
  onClick,
}) => {
  return (
    <div
      className={cn(
        "relative flex gap-2 items-center rounded-lg border bg-card p-4 transition-all duration-200 hover:bg-primary-foreground cursor-pointer",
        className,
      )}
      onClick={onClick}
    >
      <div className="flex min-w-12 items-center justify-center">{icon}</div>

      <div>
        <div className="flex items-center gap-2">
          <h3 className="comet-body-s-accented text-foreground transition-colors">
            {title}
          </h3>
          {tag && (
            <Tag variant="green" size="sm" className="h-5 leading-5">
              {tag}
            </Tag>
          )}
        </div>

        {description && (
          <p className="comet-body-xs text-muted-slate">{description}</p>
        )}
      </div>
    </div>
  );
};

export default IntegrationCard;
