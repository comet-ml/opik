import React from "react";
import { cn } from "@/lib/utils";
import { Tag } from "@/components/ui/tag";

type IntegrationCardProps = {
  title: string;
  description?: string;
  tag?: string;
  icon: React.ReactNode;
  className?: string;
  iconClassName?: string;
  onClick?: () => void;
  size?: "sm" | "lg";
  id?: string;
  "data-fs-element"?: string;
};

const IntegrationCard: React.FC<IntegrationCardProps> = ({
  title,
  description,
  tag,
  icon,
  className,
  iconClassName,
  onClick,
  size = "sm",
  id,
  "data-fs-element": dataFsElement,
}) => {
  return (
    <div
      className={cn(
        "relative flex gap-2 items-center rounded-lg border bg-background p-4 transition-all duration-200 hover:bg-primary-foreground cursor-pointer",
        size === "lg" && "min-h-[86px]",
        className,
      )}
      onClick={onClick}
      id={id}
      data-fs-element={dataFsElement}
    >
      <div
        className={cn(
          "flex min-w-12 items-center justify-center",
          iconClassName,
        )}
      >
        {icon}
      </div>

      <div>
        <div className="flex gap-2">
          <h3 className="comet-body-s-accented text-foreground transition-colors">
            {title}
          </h3>
          {tag && (
            <Tag variant="green" size="sm" className="h-5 shrink-0 leading-5">
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
