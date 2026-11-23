import React from "react";
import { ArrowDown, ArrowUp, Star } from "lucide-react";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/ui/table";
import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  PRICE_LABELS,
  describeAliasTarget,
  extraFields,
} from "@/lib/model-prices";
import {
  MAX_QUICK_ACCESS,
  PRICE_UNIT_SUFFIX,
  TYPE_TAG_VARIANT,
  formatDay,
  formatPrice,
} from "./format";

type CatalogTableProps = {
  entries: CatalogEntry[];
  sortDirection: "asc" | "desc";
  onToggleSort: () => void;
  onOpen: (entry: CatalogEntry) => void;
  isPinned: (entry: CatalogEntry) => boolean;
  canPin: boolean;
  onTogglePin: (entry: CatalogEntry) => void;
};

const HEAD_CLASS = "comet-body-xs-accented h-10 px-4 text-muted-slate";

const Price: React.FC<{ value?: string }> = ({ value }) => {
  const formatted = formatPrice(value);
  return formatted ? (
    <span className="whitespace-nowrap">
      <span className="font-mono text-foreground">{formatted}</span>
      <span className="comet-body-xs ml-1 hidden font-mono text-light-slate min-[1400px]:inline">
        {PRICE_UNIT_SUFFIX}
      </span>
    </span>
  ) : (
    <span className="text-light-slate">—</span>
  );
};

const MoreFields: React.FC<{ entry: CatalogEntry }> = ({ entry }) => {
  const fields = extraFields(entry);
  if (!fields.length) {
    return <span className="text-light-slate">—</span>;
  }
  const label = `+${fields.length} ${fields.length === 1 ? "field" : "fields"}`;
  return (
    <TooltipWrapper
      content={fields
        .map(
          (field) =>
            `${PRICE_LABELS[field]}: ${formatPrice(
              entry.prices[field],
            )} ${PRICE_UNIT_SUFFIX}`,
        )
        .join("\n")}
    >
      <span className="comet-body-s -ml-1.5 rounded px-1.5 py-0.5 hover:bg-primary-100">
        {label}
      </span>
    </TooltipWrapper>
  );
};

const WIDE_ONLY = "hidden min-[1400px]:table-cell";
const PRICE_HEAD_CLASS = cn(
  HEAD_CLASS,
  "w-[120px] text-right min-[1400px]:w-[156px]",
);

const PriceHead: React.FC<{ label: string }> = ({ label }) => (
  <TableHead className={PRICE_HEAD_CLASS}>
    {label}
    <span className="ml-1 font-normal min-[1400px]:hidden">
      {PRICE_UNIT_SUFFIX}
    </span>
  </TableHead>
);

const Cell: React.FC<{
  children: React.ReactNode;
  align?: "left" | "right";
  colSpan?: number;
  className?: string;
  cellClassName?: string;
}> = ({ children, align = "left", colSpan, className, cellClassName }) => (
  <TableCell colSpan={colSpan} className={cn("px-4", cellClassName)}>
    <div
      className={cn(
        "flex h-11 min-w-0 items-center overflow-hidden whitespace-nowrap",
        className,
      )}
    >
      {align === "right" ? (
        // justify-end would push overflow out on the left and cut the "$";
        // an auto margin keeps the start visible and clips the end instead.
        <span className="ml-auto min-w-0 truncate">{children}</span>
      ) : (
        children
      )}
    </div>
  </TableCell>
);

const CatalogTable: React.FC<CatalogTableProps> = ({
  entries,
  sortDirection,
  onToggleSort,
  onOpen,
  isPinned,
  canPin,
  onTogglePin,
}) => {
  const SortIcon = sortDirection === "asc" ? ArrowUp : ArrowDown;
  return (
    <div className="overflow-x-auto rounded-md border">
      <Table>
        <TableHeader>
          <TableRow className="bg-muted/50 hover:bg-muted/50">
            <TableHead className="h-10 w-12 px-0">
              <span className="sr-only">Pinned</span>
            </TableHead>
            <TableHead className={HEAD_CLASS}>
              <button
                type="button"
                className="inline-flex items-center gap-1 text-foreground"
                onClick={onToggleSort}
                aria-label={`Sort by model, ${
                  sortDirection === "asc" ? "A to Z" : "Z to A"
                }`}
              >
                Model
                <SortIcon className="size-3.5" />
              </button>
            </TableHead>
            <TableHead
              className={cn(HEAD_CLASS, "w-[120px] min-[1400px]:w-[160px]")}
            >
              Provider
            </TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[100px]")}>Type</TableHead>
            <PriceHead label="Input" />
            <PriceHead label="Output" />
            <TableHead className={cn(HEAD_CLASS, WIDE_ONLY, "w-[96px]")}>
              More
            </TableHead>
            <TableHead
              className={cn(HEAD_CLASS, WIDE_ONLY, "w-[120px] text-right")}
            >
              Last updated
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((entry) => {
            const pinned = isPinned(entry);
            const pinBlocked = !pinned && !canPin;
            return (
              <TableRow
                key={entry.key}
                className="group cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-inset focus-visible:ring-ring"
                onClick={() => onOpen(entry)}
                onKeyDown={(event) => {
                  if (event.key !== "Enter" && event.key !== " ") return;
                  event.preventDefault();
                  onOpen(entry);
                }}
                tabIndex={0}
                data-testid="model-price-row"
              >
                <TableCell className="w-12 px-0">
                  <TooltipWrapper
                    content={
                      pinned
                        ? "Unpin from quick access"
                        : pinBlocked
                          ? `Quick access holds ${MAX_QUICK_ACCESS} models`
                          : "Pin to quick access"
                    }
                  >
                    <button
                      type="button"
                      tabIndex={-1}
                      className={cn(
                        "mx-auto flex size-7 items-center justify-center rounded text-muted-slate",
                        pinned
                          ? "text-warning-box-text"
                          : "opacity-0 group-hover:opacity-100",
                        pinBlocked
                          ? "cursor-not-allowed group-hover:opacity-40"
                          : "hover:bg-primary-100 hover:text-foreground",
                      )}
                      aria-label={
                        pinned
                          ? `Unpin ${entry.model} from quick access`
                          : `Pin ${entry.model} to quick access`
                      }
                      aria-pressed={pinned}
                      aria-disabled={pinBlocked}
                      onClick={(event) => {
                        event.stopPropagation();
                        if (!pinBlocked) onTogglePin(entry);
                      }}
                      onKeyDown={(event) => event.stopPropagation()}
                    >
                      <Star
                        className={cn("size-3.5", pinned && "fill-current")}
                      />
                    </button>
                  </TooltipWrapper>
                </TableCell>
                <Cell>
                  <span
                    className="truncate font-mono text-[15px] leading-5"
                    title={entry.model}
                  >
                    {entry.model}
                  </span>
                </Cell>
                <Cell className="comet-body-s">
                  {entry.provider ? (
                    <span className="truncate" title={entry.provider}>
                      {entry.provider}
                    </span>
                  ) : (
                    <span className="text-light-slate">Any provider</span>
                  )}
                </Cell>
                <Cell>
                  <Tag
                    variant={TYPE_TAG_VARIANT[entry.type]}
                    className="shrink-0"
                  >
                    {CATALOG_TYPE_LABELS[entry.type]}
                  </Tag>
                </Cell>
                {entry.row?.kind === "alias" ? (
                  <Cell colSpan={2} align="right" className="comet-body-s">
                    Priced like{" "}
                    <span className="comet-code">
                      {describeAliasTarget(entry.row)}
                    </span>
                  </Cell>
                ) : (
                  <>
                    <Cell align="right">
                      <Price value={entry.prices.input_price} />
                    </Cell>
                    <Cell align="right">
                      <Price value={entry.prices.output_price} />
                    </Cell>
                  </>
                )}
                <Cell cellClassName={WIDE_ONLY}>
                  <MoreFields entry={entry} />
                </Cell>
                <Cell
                  align="right"
                  cellClassName={WIDE_ONLY}
                  className="comet-body-s text-muted-slate"
                >
                  {formatDay(entry.lastUpdatedAt) ?? "—"}
                </Cell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
};

export default CatalogTable;
