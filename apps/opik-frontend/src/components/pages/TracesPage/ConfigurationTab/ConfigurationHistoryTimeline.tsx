import React from "react";
import { Clock, FilePen, User } from "lucide-react";

import { cn } from "@/lib/utils";
import { ConfigHistoryItem } from "@/types/optimizer-configs";
import { getTimeFromNow } from "@/lib/date";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ProdTag from "./ProdTag";
import { isProdTag, sortTags } from "./utils/agent-configurations";

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
    <ul className="p-4">
      {items.map((item, index) => {
        const isLatest = index === 0;
        const isSelected = index === selectedIndex;
        const isLast = index === items.length - 1;
        const sortedTags = sortTags(item.tags);

        return (
          <li key={item.id} className="flex gap-2">
            <div className="flex flex-col items-center">
              <div
                className={cn(
                  "h-3 w-0.5 shrink-0",
                  index > 0 ? "bg-[#cfd0ff] opacity-50" : "bg-transparent",
                )}
              />
              <div
                className={cn(
                  "size-2 shrink-0 rounded-full",
                  isSelected ? "bg-primary" : "border-2 border-[#cfd0ff]",
                )}
              />
              <div
                className={cn(
                  "w-0.5 flex-1 shrink-0",
                  !isLast ? "bg-[#cfd0ff] opacity-50" : "bg-transparent",
                )}
              />
            </div>

            {/* Card */}
            <div
              className={cn(
                "min-w-0 flex-1 cursor-pointer rounded px-3 py-2 transition-colors",
                isSelected
                  ? "bg-primary-foreground ring-1 ring-primary"
                  : "hover:bg-primary-foreground/60",
              )}
              onClick={() => onSelect(index)}
            >
              <div className="flex flex-wrap items-center gap-1">
                <span className="comet-body-s-accented">v{total - index}</span>
                {sortedTags.map((tag) =>
                  isProdTag(tag) ? (
                    <ProdTag key={tag} size="xs" />
                  ) : (
                    <ColoredTag key={tag} label={tag} size="sm" />
                  ),
                )}
              </div>
              <p className="comet-body-xs mt-1.5 flex items-center gap-1 truncate text-light-slate">
                <FilePen className="size-3 shrink-0" />
                {item.description}
              </p>
              <div className="comet-body-xs mt-1.5 flex items-center gap-3 text-light-slate">
                <span className="flex items-center gap-1">
                  <Clock className="size-3 shrink-0" />
                  {getTimeFromNow(item.createdAt)}
                </span>
                <span className="flex items-center gap-1">
                  <User className="size-3 shrink-0" />
                  {item.createdBy}
                </span>
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

export default ConfigurationHistoryTimeline;
