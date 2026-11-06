import React from "react";
import { Star } from "lucide-react";
import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { CATALOG_TYPE_LABELS, CatalogEntry } from "@/lib/model-prices";
import { TYPE_TAG_VARIANT, formatPrice } from "./format";

type QuickAccessProps = {
  entries: CatalogEntry[];
  onOpen: (entry: CatalogEntry) => void;
  isPinned: (entry: CatalogEntry) => boolean;
  onTogglePin: (entry: CatalogEntry) => void;
};

const QuickAccess: React.FC<QuickAccessProps> = ({
  entries,
  onOpen,
  isPinned,
  onTogglePin,
}) => {
  if (!entries.length) {
    return null;
  }
  return (
    <div className="mb-6" data-testid="quick-access">
      <h3 className="comet-body-s-accented mb-2 flex items-center gap-1.5">
        <Star className="size-3.5" />
        Quick access
      </h3>
      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        {entries.map((entry) => {
          const pinned = isPinned(entry);
          return (
            <div
              key={entry.key}
              className="relative flex min-w-0 flex-col gap-1 rounded-md border bg-background p-3 hover:border-primary"
            >
              <span className="flex items-start justify-between gap-2">
                {/* ::after stretches this button over the card, so the whole
                    card is clickable without nesting the pin inside a button. */}
                <button
                  type="button"
                  className="min-w-0 truncate text-left font-mono text-[15px] font-semibold leading-5 outline-none after:absolute after:inset-0 after:rounded-md after:content-[''] focus-visible:after:ring-1 focus-visible:after:ring-ring"
                  title={entry.model}
                  aria-label={`Open ${entry.model}`}
                  onClick={() => onOpen(entry)}
                >
                  {entry.model}
                </button>
                <span className="flex shrink-0 items-center gap-1">
                  <TooltipWrapper
                    content={
                      pinned ? "Unpin from quick access" : "Pin to quick access"
                    }
                  >
                    <button
                      type="button"
                      className={cn(
                        "relative z-10 flex size-5 items-center justify-center rounded hover:bg-primary-100",
                        pinned ? "text-warning-box-text" : "text-light-slate",
                      )}
                      aria-label={
                        pinned
                          ? `Unpin ${entry.model} from quick access`
                          : `Pin ${entry.model} to quick access`
                      }
                      aria-pressed={pinned}
                      onClick={() => onTogglePin(entry)}
                    >
                      <Star
                        className={cn("size-3.5", pinned && "fill-current")}
                      />
                    </button>
                  </TooltipWrapper>
                  <Tag
                    variant={TYPE_TAG_VARIANT[entry.type]}
                    className="shrink-0"
                  >
                    {CATALOG_TYPE_LABELS[entry.type]}
                  </Tag>
                </span>
              </span>
              <span className="comet-body-xs text-muted-slate">
                {entry.provider || "Any provider"}
              </span>
              <span className="comet-body-xs font-mono text-muted-slate">
                in{" "}
                <span className="text-foreground">
                  {formatPrice(entry.prices.input_price) ?? "—"}
                </span>
                <span className="ml-2">out</span>{" "}
                <span className="text-foreground">
                  {formatPrice(entry.prices.output_price) ?? "—"}
                </span>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default QuickAccess;
