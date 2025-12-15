import React from "react";
import { useVisibleTags } from "@/hooks/useVisibleTags";

interface TagListTooltipContentProps {
  tags: string[];
  label?: string;
  maxDisplayCount?: number;
}

const TagListTooltipContent: React.FC<TagListTooltipContentProps> = ({
  tags,
  label = "tags",
  maxDisplayCount = 50,
}) => {
  const { visibleItems, hasMoreItems, remainingCount } = useVisibleTags(
    tags,
    maxDisplayCount,
  );

  return (
    <div className="flex max-w-[200px] flex-col gap-1">
      <span className="text-xs font-medium">
        All {label} ({tags.length}):
      </span>
      <div className="flex max-h-[300px] flex-col gap-1 overflow-y-auto">
        {visibleItems.map((tag) => (
          <span key={tag} className="text-xs text-muted-foreground">
            {tag}
          </span>
        ))}
        {hasMoreItems && (
          <span className="text-xs italic text-muted-foreground">
            ... and {remainingCount} more
          </span>
        )}
      </div>
    </div>
  );
};

export default TagListTooltipContent;
