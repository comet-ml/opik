import React, { useMemo } from "react";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { Tag, TagProps } from "@/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { CircleX } from "lucide-react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export interface RemovableTagProps {
  label: string;
  size?: TagProps["size"];
  variant?: TagProps["variant"];
  onClick?: (tag: string) => void;
  onDelete?: (tag: string) => void;
  className?: string;
}

const RemovableTag: React.FunctionComponent<RemovableTagProps> = ({
  label,
  size = "default",
  variant,
  onClick,
  onDelete,
  className,
}) => {
  const calculatedVariant = useMemo(
    () => variant ?? generateTagVariant(label),
    [label, variant],
  );

  const isRemovable = isFunction(onDelete);
  const isClickable = isFunction(onClick);

  const handleClick = () => {
    onClick?.(label);
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (!isClickable) return;

    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      handleClick();
    }
  };

  return (
    <TooltipWrapper content={label}>
      <Tag
        size={size}
        variant={calculatedVariant}
        role={isClickable ? "button" : undefined}
        tabIndex={isClickable ? 0 : undefined}
        onClick={isClickable ? handleClick : undefined}
        onKeyDown={handleKeyDown}
        className={cn(
          "group relative max-w-40 shrink-0 text-center transition-all",
          isClickable && "cursor-pointer",
          className,
        )}
      >
        <span className="block truncate">{label}</span>
        {isRemovable && (
          <span className="pointer-events-none absolute inset-y-0 right-0 hidden items-center rounded-r-[inherit] bg-inherit pl-3 [mask-image:linear-gradient(to_right,transparent,black_40%)] group-hover:flex">
            <span
              className="pointer-events-auto flex h-full w-5 cursor-pointer items-center justify-center"
              aria-label={`Remove ${label}`}
              onClick={(event) => {
                event.stopPropagation();
                onDelete(label);
              }}
            >
              <CircleX className="size-3.5" />
            </span>
          </span>
        )}
      </Tag>
    </TooltipWrapper>
  );
};

export default RemovableTag;
