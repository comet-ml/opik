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
              role="button"
              tabIndex={0}
              aria-label={`Open ${entry.model}`}
              className="flex min-w-0 cursor-pointer flex-col gap-1 rounded-md border bg-background p-3 text-left outline-none hover:border-primary focus-visible:ring-1 focus-visible:ring-ring"
              onClick={() => onOpen(entry)}
              onKeyDown={(event) => {
                if (event.key !== "Enter" && event.key !== " ") return;
                event.preventDefault();
                onOpen(entry);
              }}
            >
              <span className="flex items-start justify-between gap-2">
                <span
                  className="truncate font-mono text-[15px] font-semibold leading-5"
                  title={entry.model}
                >
                  {entry.model}
                </span>
                <span className="flex shrink-0 items-center gap-1">
                  <TooltipWrapper
                    content={
                      pinned ? "Unpin from quick access" : "Pin to quick access"
                    }
                  >
                    <button
                      type="button"
                      className={cn(
                        "flex size-5 items-center justify-center rounded hover:bg-primary-100",
                        pinned ? "text-warning-box-text" : "text-light-slate",
                      )}
                      aria-label={
                        pinned
                          ? `Unpin ${entry.model} from quick access`
                          : `Pin ${entry.model} to quick access`
                      }
                      aria-pressed={pinned}
                      onClick={(event) => {
                        event.stopPropagation();
                        onTogglePin(entry);
                      }}
                      onKeyDown={(event) => event.stopPropagation()}
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
                {"  "}out{" "}
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
