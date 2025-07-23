import React, { useMemo } from "react";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { Tag, TagProps } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { Button } from "@/components/ui/button";
import { CircleX } from "lucide-react";

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
    <Tag
      size={size}
      variant={calculatedVariant}
      className={cn("group max-w-full shrink-0 pr-2 transition-all", className)}
    >
      <div className="flex max-w-full items-center">
        <span className="mr-1 truncate">{label}</span>
        {isRemovable && (
          <Button
            size="icon-2xs"
            variant="ghost"
            className="hidden group-hover:flex"
            onClick={() => onDelete(label)}
          >
            <CircleX />
          </Button>
        )}
      </div>
    </Tag>
  );
};

export default RemovableTag;
