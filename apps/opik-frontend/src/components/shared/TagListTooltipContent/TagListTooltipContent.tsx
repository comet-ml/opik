import React from "react";
import { useVisibleTags } from "@/hooks/useVisibleTags";

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
        <React.Fragment key={`${tag}-${index}`}>
          {index > 0 && (
            <span className="text-xs text-muted-foreground">•</span>
          )}
          <span className="text-xs text-muted-foreground">{tag}</span>
        </React.Fragment>
      ))}
      {hasMoreItems && (
        <>
          <span className="text-xs text-muted-foreground">•</span>
          <span className="text-xs italic text-muted-foreground">
            ... and {remainingCount} more
          </span>
        </>
      )}
    </div>
  );
};

export default TagListTooltipContent;
