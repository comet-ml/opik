import React from "react";
import { Wrench } from "lucide-react";

import { cn } from "@/lib/utils";
import { Tag, TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { ConfigHistoryItem } from "@/types/optimizer-configs";
import { generateTagVariant } from "@/lib/traces";
import { getTimeFromNow } from "@/lib/date";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";

type ConfigurationHistoryTimelineProps = {
  items: ConfigHistoryItem[];
  total: number;
  selectedIndex: number | null;
  onSelect: (index: number) => void;
};

const ConfigurationHistoryTimeline: React.FC<
  ConfigurationHistoryTimelineProps
> = ({ items, total, selectedIndex, onSelect }) => {
  if (items.length === 0) {
    return <DataTableNoData title="No configuration history" />;
  }

  return (
    <ul className="px-4 py-4">
      {items.map((item, index) => {
        const isLatest = index === 0;
        const isSelected = index === selectedIndex;
        const iconColor = isLatest
          ? TAG_VARIANTS_COLOR_MAP["blue"]
          : item.tags[0]
            ? TAG_VARIANTS_COLOR_MAP[
                generateTagVariant(
                  item.tags[0],
                ) as keyof typeof TAG_VARIANTS_COLOR_MAP
              ]
            : undefined;

        return (
          <li
            key={item.id}
            className={cn(
              "relative flex cursor-pointer items-start gap-3 rounded px-2 py-3 transition-colors",
              isSelected
                ? "bg-primary-foreground"
                : "hover:bg-primary-foreground/60",
            )}
            onClick={() => onSelect(index)}
          >
            {/* Timeline connector line to next item */}
            {index < items.length - 1 && (
              <div className="absolute bottom-0 left-[1.75rem] top-9 w-px bg-border" />
            )}

            {/* Circle icon */}
            <div
              className="relative z-10 flex size-6 shrink-0 items-center justify-center rounded-full border bg-background"
              style={
                iconColor
                  ? {
                      borderColor: iconColor,
                      backgroundColor: `color-mix(in srgb, ${iconColor} 15%, var(--background))`,
                    }
                  : undefined
              }
            >
              <Wrench
                className="size-3"
                style={iconColor ? { color: iconColor } : undefined}
              />
            </div>

            {/* Content */}
            <div className="min-w-0 flex-1 pb-1">
              <div className="flex flex-wrap items-center gap-1">
                <span className="comet-body-s-accented">
                  v{total - index}
                </span>
                {isLatest && (
                  <Tag size="sm" variant="blue">
                    latest
                  </Tag>
                )}
                {item.tags[0] && (
                  <ColoredTag label={item.tags[0]} size="sm" />
                )}
              </div>
              <p className="comet-body-xs mt-0.5 truncate text-light-slate">
                {item.description}
              </p>
              <p className="comet-body-xs mt-0.5 text-muted-slate">
                {getTimeFromNow(item.createdAt)}
              </p>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

export default ConfigurationHistoryTimeline;
