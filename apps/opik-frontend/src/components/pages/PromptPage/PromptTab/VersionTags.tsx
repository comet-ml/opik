import React from "react";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import TagListTooltipContent from "@/components/shared/TagListTooltipContent/TagListTooltipContent";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useVisibleTags } from "@/hooks/useVisibleTags";
import { cn } from "@/lib/utils";

interface VersionTagsProps {
  tags: string[];
  containerClassName?: string;
  maxVisibleTags?: number;
}

const VersionTags: React.FC<VersionTagsProps> = ({
  tags,
  containerClassName,
  maxVisibleTags,
}) => {
  const { visibleItems, hasMoreItems, remainingCount } = useVisibleTags(
    tags,
    maxVisibleTags,
  );

  if (!tags || tags.length === 0) return null;

  return (
    <div
      className={cn(
        "flex max-w-[200px] shrink flex-nowrap items-center gap-1 overflow-hidden",
        containerClassName,
      )}
    >
      {visibleItems.map((tag) => (
        <ColoredTag
          key={tag}
          label={tag}
          size="sm"
          className="min-w-0 max-w-[80px] shrink truncate"
        />
      ))}
      {hasMoreItems && (
        <TooltipWrapper content={<TagListTooltipContent tags={tags} />}>
          <div className="comet-body-s-accented flex h-4 items-center rounded-md border border-border pl-1 pr-1.5 text-[9px] text-muted-slate">
            +{remainingCount}
          </div>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default VersionTags;
