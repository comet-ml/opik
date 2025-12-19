import React from "react";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import TagListTooltipContent from "@/components/shared/TagListTooltipContent/TagListTooltipContent";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useVisibleTags } from "@/hooks/useVisibleTags";

interface VersionTagsProps {
  tags: string[];
}

const VersionTags: React.FC<VersionTagsProps> = ({ tags }) => {
  const { visibleItems, hiddenItems, hasMoreItems, remainingCount } =
    useVisibleTags(tags);

  if (!tags || tags.length === 0) return null;

  return (
    <div className="flex max-w-[160px] shrink flex-nowrap items-center gap-0.5 overflow-hidden">
      {visibleItems.map((tag) => (
        <ColoredTag
          key={tag}
          label={tag}
          size="sm"
          className="min-w-0 max-w-[65px] shrink origin-left scale-[0.85] truncate"
        />
      ))}
      {hasMoreItems && (
        <TooltipWrapper content={<TagListTooltipContent tags={hiddenItems} />}>
          <div className="comet-body-s-accented flex h-4 items-center rounded-md border border-border pl-1 pr-1.5 text-[9px] text-muted-slate">
            +{remainingCount}
          </div>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default VersionTags;
