import React from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

type StatusMessageProps = {
  title: string;
  description: string;
  className?: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  iconClassName?: string;
};

const StatusMessage: React.FC<StatusMessageProps> = ({
  title,
  description,
  className,
  icon: Icon,
  iconClassName,
}) => {
  return (
    <Card className={cn("p-4 bg-primary-foreground shadow-none", className)}>
      <div className="flex items-start gap-3">
        <Icon
          className={cn(
            "mt-0.5 size-4 shrink-0 text-muted-slate",
            iconClassName,
          )}
        />
        <div className="flex flex-col">
          <span className="comet-body-s-accented text-foreground-secondary">
            {title}
          </span>
          <span className="comet-body-s text-muted-slate">{description}</span>
        </div>
      </div>
    </Card>
  );
};

export default StatusMessage;
