import React from "react";

import { cn } from "@/lib/utils";
import { Tag } from "@/components/ui/tag";
import { ConfigHistoryItem } from "@/types/optimizer-configs";
import { getTimeFromNow } from "@/lib/date";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";

type ConfigurationHistoryTimelineProps = {
  items: ConfigHistoryItem[];
  total: number;
  selectedIndex: number | null;
  onSelect: (index: number) => void;
};

// ─── Timeline connector line layout ──────────────────────────────────────────
//  Each <li> uses:  py-3 (12 px top/bottom)  ·  px-2 (8 px left)
//  Circle:          size-4 (16 px)
//
//  One connector per gap, rendered inside the upper item.
//  It starts at the bottom of the current circle and ends exactly at the top
//  of the next circle by extending 12 px past the li's bottom edge:
//
//    CONNECTOR_LEFT   — circle center horizontally
//                       px-2 (8 px) + size-4 / 2 (8 px) = 16 px  →  left-4
//
//    CONNECTOR_TOP    — circle bottom (line start)
//                       py-3 (12 px) + size-4 (16 px) = 28 px     →  top-7
//
//    CONNECTOR_BOTTOM — extends into next item's top padding (line end)
//                       -py-3 = -12 px                             →  -bottom-3
//                       lands exactly at the next circle's top edge
//
//  Each <li> gets style={{ zIndex: items.length - index }} so earlier items
//  stack above later ones. This prevents the next item's hover/selected
//  background from painting over the connector extension.
// ─────────────────────────────────────────────────────────────────────────────
const CONNECTOR_LEFT = "left-4";
const CONNECTOR_TOP = "top-7";
const CONNECTOR_BOTTOM = "-bottom-3";

const ConfigurationHistoryTimeline: React.FC<
  ConfigurationHistoryTimelineProps
> = ({ items, total, selectedIndex, onSelect }) => {
  if (items.length === 0) {
    return <DataTableNoData title="No configuration history" />;
  }

  return (
    <ul className="p-4">
      {items.map((item, index) => {
        const isLatest = index === 0;
        const isSelected = index === selectedIndex;

        return (
          <li
            key={item.id}
            style={{ zIndex: items.length - index }}
            className={cn(
              "relative flex cursor-pointer items-start gap-3 rounded px-2 py-3 transition-colors",
              isSelected
                ? "bg-primary-foreground"
                : "hover:bg-primary-foreground/60",
            )}
            onClick={() => onSelect(index)}
          >
            {/* Single connector: this circle-bottom → next circle-top */}
            {index < items.length - 1 && (
              <div
                className={cn(
                  "absolute w-px",
                  CONNECTOR_LEFT,
                  CONNECTOR_TOP,
                  CONNECTOR_BOTTOM,
                  isSelected ? "bg-primary" : "bg-border",
                )}
              />
            )}

            {/* Circle */}
            <div
              className={cn(
                "relative z-10 flex size-4 shrink-0 items-center justify-center rounded-full bg-background transition-colors",
                isSelected ? "border border-primary" : "border-2 border-border",
              )}
            >
              {isSelected && (
                <div className="size-1.5 rounded-full bg-primary" />
              )}
            </div>

            {/* Content */}
            <div className="min-w-0 flex-1 pb-1">
              <div className="flex flex-wrap items-center gap-1">
                <span className="comet-body-s-accented">v{total - index}</span>
                {isLatest && (
                  <Tag size="sm" variant="blue">
                    latest
                  </Tag>
                )}
                {item.tags[0] && <ColoredTag label={item.tags[0]} size="sm" />}
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
