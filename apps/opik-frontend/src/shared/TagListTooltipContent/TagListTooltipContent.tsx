import React from "react";
import { useVisibleTags } from "@/hooks/useVisibleTags";
import ColoredTag, { ColoredTagProps } from "@/shared/ColoredTag/ColoredTag";
import { Separator } from "@/ui/separator";

interface TagListTooltipContentProps {
  tags: string[];
  maxDisplayCount?: number;
  variant?: ColoredTagProps["variant"];
  size?: ColoredTagProps["size"];
  hint?: string;
}

const TagListTooltipContent: React.FC<TagListTooltipContentProps> = ({
  tags,
  maxDisplayCount = 50,
  variant,
  size = "sm",
  hint,
}) => {
  const { visibleItems, hasMoreItems, remainingCount } = useVisibleTags(
    tags,
    maxDisplayCount,
  );

  return (
    <div className="flex max-w-[300px] flex-wrap gap-1">
      {visibleItems.map((tag, index) => (
        <ColoredTag
          key={`${tag}-${index}`}
          label={tag}
          size={size}
          variant={variant}
        />
      ))}
      {hasMoreItems && (
        <span className="text-xs italic text-muted-foreground">
          ... and {remainingCount} more
        </span>
      )}

      {hint && (
        <>
          <Separator className="my-1" />
          <div className="comet-body-xs w-full px-1 text-muted-slate">
            {hint}
          </div>
        </>
      )}
    </div>
  );
};

export default TagListTooltipContent;
