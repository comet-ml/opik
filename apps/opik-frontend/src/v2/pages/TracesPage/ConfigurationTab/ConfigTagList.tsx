import React from "react";

import { isBasicStage, sortTags } from "@/utils/agent-configurations";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";
import ChildrenWidthMeasurer from "@/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BasicStageTag from "./BasicStageTag";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";

type ConfigTagListSize = "sm" | "md";

type ConfigTagListProps = {
  tags: string[];
  size?: ConfigTagListSize;
  maxWidth?: number;
  className?: string;
};

const TAGS_CONFIG = { itemGap: 4 };

const TAG_SIZE_MAP: Record<
  ConfigTagListSize,
  { basic: "xs" | "sm"; colored: "sm" | "md" }
> = {
  sm: { basic: "xs", colored: "sm" },
  md: { basic: "sm", colored: "md" },
};

const COUNTER_CLASSES: Record<ConfigTagListSize, string> = {
  sm: "comet-body-xs-accented flex h-4 shrink-0 items-center rounded border border-border px-1 text-[11px] leading-4 text-muted-slate",
  md: "comet-body-s-accented flex h-6 shrink-0 items-center rounded-md border border-border pl-1 pr-1.5 text-muted-slate",
};

const renderTag = (tag: string, size: ConfigTagListSize) =>
  isBasicStage(tag) ? (
    <BasicStageTag key={tag} value={tag} size={TAG_SIZE_MAP[size].basic} />
  ) : (
    <ColoredTag key={tag} label={tag} size={TAG_SIZE_MAP[size].colored} />
  );

const ConfigTagList: React.FC<ConfigTagListProps> = ({
  tags,
  size = "md",
  maxWidth,
  className,
}) => {
  const sortedTags = sortTags(tags);

  const {
    cellRef,
    visibleItems,
    hiddenItems,
    hasHiddenItems,
    remainingCount,
    onMeasure,
  } = useVisibleItemsByWidth(sortedTags, TAGS_CONFIG);

  if (sortedTags.length === 0) return null;

  return (
    <div
      className={`relative flex items-center gap-1 overflow-hidden ${
        maxWidth ? "max-w-[var(--tag-list-width)]" : ""
      } ${className ?? ""}`}
      style={
        maxWidth
          ? ({ "--tag-list-width": `${maxWidth}px` } as React.CSSProperties)
          : undefined
      }
    >
      <div
        ref={cellRef}
        className={`pointer-events-none invisible absolute ${
          maxWidth ? "w-[var(--tag-list-width)]" : "w-full"
        }`}
      />
      <ChildrenWidthMeasurer onMeasure={onMeasure}>
        {sortedTags.map((tag) => (
          <div key={tag}>{renderTag(tag, size)}</div>
        ))}
      </ChildrenWidthMeasurer>
      {(visibleItems.length > 0 ? visibleItems : sortedTags).map((tag) =>
        renderTag(tag, size),
      )}
      {hasHiddenItems && (
        <TooltipWrapper
          content={
            <div className="flex max-w-[300px] flex-wrap gap-1">
              {hiddenItems.map((tag) => renderTag(tag, size))}
            </div>
          }
        >
          <div className={COUNTER_CLASSES[size]}>+{remainingCount}</div>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default ConfigTagList;
