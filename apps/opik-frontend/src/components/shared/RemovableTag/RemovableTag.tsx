import React, { useMemo } from "react";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { Tag, TagProps } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { CircleX } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export interface RemovableTagProps {
  label: string;
  size?: TagProps["size"];
  variant?: TagProps["variant"];
  onDelete?: (tag: string) => void;
  className?: string;
}

const RemovableTag: React.FunctionComponent<RemovableTagProps> = ({
  label,
  size = "default",
  variant,
  onDelete,
  className,
}) => {
  const calculatedVariant = useMemo(
    () => variant ?? generateTagVariant(label),
    [label, variant],
  );

  const isRemovable = isFunction(onDelete);

  return (
    <TooltipWrapper content={label}>
      <Tag
        size={size}
        variant={calculatedVariant}
        className={cn(
          "group relative max-w-40 shrink-0 text-center transition-all",
          className,
        )}
      >
        <span className="block truncate">{label}</span>
        {isRemovable && (
          <span className="pointer-events-none absolute inset-y-0 right-0 hidden items-center rounded-r-[inherit] bg-inherit pl-3 [mask-image:linear-gradient(to_right,transparent,black_40%)] group-hover:flex">
            <span
              className="pointer-events-auto flex h-full w-5 cursor-pointer items-center justify-center"
              onClick={() => onDelete(label)}
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
