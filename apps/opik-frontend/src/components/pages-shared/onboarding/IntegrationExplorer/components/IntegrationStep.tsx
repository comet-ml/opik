import { FC } from "react";
import { cn } from "@/lib/utils";

interface IntegrationStepProps {
  title: string;
  description?: string;
  className?: string;
  children?: React.ReactNode;
}

export const IntegrationStep: FC<IntegrationStepProps> = ({
  title,
  description,
  className,
  children,
}) => {
  return (
    <div
      className={cn(
        "flex flex-col rounded-md border bg-background p-4",
        className,
      )}
    >
      <div className="space-y-1">
        <div className="comet-body-s-accented">{title}</div>
        {description && (
          <div className="comet-body-s text-muted-slate">{description}</div>
        )}
      </div>

      <div className="pt-3">{children}</div>
    </div>
  );
};
