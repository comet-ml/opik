import React from "react";
import { TagProps } from "@/ui/tag";
import { cn } from "@/lib/utils";
import { useVisibleItemsByWidth } from "@/hooks/useVisibleItemsByWidth";
import ChildrenWidthMeasurer from "@/shared/ChildrenWidthMeasurer/ChildrenWidthMeasurer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import EnvironmentBadge from "./EnvironmentBadge";
import { EnvironmentSquareWithTooltip } from "./EnvironmentLabel";

type EnvironmentBadgeListProps = {
  names: string[] | null | undefined;
  size?: TagProps["size"];
  className?: string;
  badgeClassName?: string;
  withOverflow?: boolean;
  maxWidth?: number;
  compact?: boolean;
};

const COUNTER_CLASSES: Record<NonNullable<TagProps["size"]>, string> = {
  default: "comet-body-xs h-5 px-1.5 leading-5 rounded-sm",
  sm: "comet-body-xs h-4 px-1.5 text-[11px] leading-4 rounded-sm",
  md: "comet-body-s h-6 px-1.5 leading-6 rounded-md",
  lg: "comet-body-s h-7 px-2 leading-7 rounded-md",
};

const OVERFLOW_CONFIG = {
  itemGap: 4,
  counterWidth: 30,
  containerPadding: 0,
  minFirstItemWidth: 40,
};

const EnvironmentBadgeList: React.FC<EnvironmentBadgeListProps> = ({
  names,
  size = "sm",
  className,
  badgeClassName,
  withOverflow = false,
  maxWidth,
  compact = false,
}) => {
  const list = names ?? [];
  const {
    cellRef,
    visibleItems,
    hiddenItems,
    hasHiddenItems,
    remainingCount,
    onMeasure,
  } = useVisibleItemsByWidth(list, OVERFLOW_CONFIG);

  if (list.length === 0) return null;

  const renderItem = (name: string) =>
    compact ? (
      <EnvironmentSquareWithTooltip key={name} name={name} />
    ) : (
      <EnvironmentBadge
        key={name}
        name={name}
        size={size}
        className={badgeClassName}
      />
    );

  if (!withOverflow) {
    return (
      <div className={cn("flex flex-wrap items-center gap-1", className)}>
        {list.map(renderItem)}
      </div>
    );
  }

  const itemsToRender = visibleItems.length > 0 ? visibleItems : list;

  return (
    <div
      className={cn(
        "relative flex min-w-0 items-center gap-1 overflow-hidden",
        maxWidth && "max-w-[var(--env-list-width)]",
        className,
      )}
      style={
        maxWidth
          ? ({ "--env-list-width": `${maxWidth}px` } as React.CSSProperties)
          : undefined
      }
    >
      <div
        ref={cellRef}
        className={cn(
          "pointer-events-none invisible absolute",
          maxWidth ? "w-[var(--env-list-width)]" : "inset-0",
        )}
      />
      <ChildrenWidthMeasurer onMeasure={onMeasure}>
        {list.map((name) => (
          <div key={name}>{renderItem(name)}</div>
        ))}
      </ChildrenWidthMeasurer>
      {itemsToRender.map(renderItem)}
      {hasHiddenItems && (
        <TooltipWrapper
          content={
            <div className="flex max-w-[300px] flex-wrap gap-1">
              {hiddenItems.map((name) => (
                <EnvironmentBadge key={name} name={name} size={size} />
              ))}
            </div>
          }
        >
          <div
            className={cn(
              "flex shrink-0 items-center justify-center border border-border text-muted-slate",
              compact
                ? "comet-body-xs h-[18px] min-w-[18px] px-0.5 text-[10px] leading-none rounded-[0.2rem]"
                : COUNTER_CLASSES[size ?? "sm"],
            )}
          >
            +{remainingCount}
          </div>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default EnvironmentBadgeList;
