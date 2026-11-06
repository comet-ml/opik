import React from "react";
import { Star } from "lucide-react";
import { Tag } from "@/ui/tag";
import { CATALOG_TYPE_LABELS, CatalogEntry } from "@/lib/model-prices";
import { TYPE_TAG_VARIANT, formatPrice } from "./format";

type QuickAccessProps = {
  entries: CatalogEntry[];
  onOpen: (entry: CatalogEntry) => void;
};

const QuickAccess: React.FC<QuickAccessProps> = ({ entries, onOpen }) => {
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
        {entries.map((entry) => (
          <button
            key={entry.key}
            type="button"
            className="flex min-w-0 flex-col gap-1 rounded-md border bg-background p-3 text-left hover:border-primary"
            onClick={() => onOpen(entry)}
          >
            <span className="flex items-start justify-between gap-2">
              <span className="truncate font-mono text-[15px] font-medium leading-5">
                {entry.model}
              </span>
              <Tag
                variant={TYPE_TAG_VARIANT[entry.type]}
                size="sm"
                className="shrink-0"
              >
                {CATALOG_TYPE_LABELS[entry.type]}
              </Tag>
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
          </button>
        ))}
      </div>
    </div>
  );
};

export default QuickAccess;
