import React, { useMemo } from "react";

import { Tag } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { Button } from "@/components/ui/button";
import { CircleX } from "lucide-react";

export interface RemovableTagProps {
  label: string;
  size?: "default" | "sm" | "lg";
  onDelete: (tag: string) => void;
}

const RemovableTag: React.FunctionComponent<RemovableTagProps> = ({
  label,
  size = "default",
  onDelete,
}) => {
  const variant = useMemo(() => generateTagVariant(label), [label]);

  return (
    <Tag
      size={size}
      variant={variant}
      className="group max-w-full shrink-0 pr-2 transition-all"
    >
      <div className="flex max-w-full items-center">
        <span className="mr-1 truncate">{label}</span>
        <Button
          size="icon-xs"
          variant="ghost"
          className="hidden group-hover:flex"
          onClick={() => onDelete(label)}
        >
          <CircleX className="size-4" />
        </Button>
      </div>
    </Tag>
  );
};

export default RemovableTag;
