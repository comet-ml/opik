import React from "react";
import { useVisibleTags } from "@/hooks/useVisibleTags";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";

interface TagListTooltipContentProps {
  tags: string[];
  maxDisplayCount?: number;
}

const TagListTooltipContent: React.FC<TagListTooltipContentProps> = ({
  tags,
  maxDisplayCount = 50,
}) => {
  const { visibleItems, hasMoreItems, remainingCount } = useVisibleTags(
    tags,
    maxDisplayCount,
  );

  return (
    <div className="flex max-w-[300px] flex-wrap gap-1">
      {visibleItems.map((tag, index) => (
        <ColoredTag key={`${tag}-${index}`} label={tag} size="sm" />
      ))}
      {hasMoreItems && (
        <span className="text-xs italic text-muted-foreground">
          ... and {remainingCount} more
        </span>
      )}
    </div>
  );
};

export default TagListTooltipContent;
