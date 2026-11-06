import React from "react";
import { ArrowDown, ArrowUp, Pin } from "lucide-react";
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
import { TYPE_TAG_VARIANT, formatDay, formatPrice } from "./format";

type CatalogTableProps = {
  entries: CatalogEntry[];
  sortDirection: "asc" | "desc";
  onToggleSort: () => void;
  onOpen: (entry: CatalogEntry) => void;
  isPinned: (entry: CatalogEntry) => boolean;
  onTogglePin: (entry: CatalogEntry) => void;
};

const HEAD_CLASS = "comet-body-xs-accented h-10 px-4 text-muted-slate";

const Price: React.FC<{ value?: string }> = ({ value }) => {
  const formatted = formatPrice(value);
  return formatted ? (
    <span className="whitespace-nowrap">
      <span className="font-mono text-foreground">{formatted}</span>
      <span className="comet-body-xs ml-1 font-mono text-light-slate">
        /1M tok
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
            )} /1M tok`,
        )
        .join("\n")}
    >
      <span className="comet-body-s -ml-1.5 rounded px-1.5 py-0.5 hover:bg-primary-100">
        {label}
      </span>
    </TooltipWrapper>
  );
};

const Cell: React.FC<{
  children: React.ReactNode;
  align?: "left" | "right";
  colSpan?: number;
  className?: string;
}> = ({ children, align = "left", colSpan, className }) => (
  <TableCell colSpan={colSpan} className="px-4">
    <div
      className={cn(
        "flex h-11 min-w-0 items-center whitespace-nowrap",
        align === "right" && "justify-end",
        className,
      )}
    >
      {children}
    </div>
  </TableCell>
);

const CatalogTable: React.FC<CatalogTableProps> = ({
  entries,
  sortDirection,
  onToggleSort,
  onOpen,
  isPinned,
  onTogglePin,
}) => {
  const SortIcon = sortDirection === "asc" ? ArrowUp : ArrowDown;
  return (
    <div className="overflow-x-auto rounded-md border">
      <Table className="min-w-[960px]">
        <TableHeader>
          <TableRow className="bg-muted/50 hover:bg-muted/50">
            <TableHead className="h-10 w-12 px-0">
              <span className="sr-only">Pinned</span>
            </TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[30%]")}>
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
            <TableHead className={cn(HEAD_CLASS, "w-[13%]")}>
              Provider
            </TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[9%]")}>Type</TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[12%] text-right")}>
              Input
            </TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[12%] text-right")}>
              Output
            </TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[10%]")}>More</TableHead>
            <TableHead className={cn(HEAD_CLASS, "w-[14%] text-right")}>
              Last updated
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((entry) => {
            const pinned = isPinned(entry);
            return (
              <TableRow
                key={entry.key}
                className="group cursor-pointer"
                onClick={() => onOpen(entry)}
                onKeyDown={(event) => event.key === "Enter" && onOpen(entry)}
                tabIndex={0}
                data-testid="model-price-row"
              >
                <TableCell className="w-12 px-0">
                  <button
                    type="button"
                    className={cn(
                      "mx-auto flex size-7 items-center justify-center rounded text-muted-slate hover:bg-primary-100 hover:text-foreground focus-visible:opacity-100",
                      pinned
                        ? "text-foreground"
                        : "opacity-0 group-hover:opacity-100",
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
                    <Pin className={cn("size-3.5", pinned && "fill-current")} />
                  </button>
                </TableCell>
                <Cell>
                  <span className="truncate font-mono text-[15px] leading-5">
                    {entry.model}
                  </span>
                </Cell>
                <Cell className="comet-body-s">
                  {entry.provider || (
                    <span className="text-light-slate">Any provider</span>
                  )}
                </Cell>
                <Cell>
                  <Tag
                    variant={TYPE_TAG_VARIANT[entry.type]}
                    size="sm"
                    className="shrink-0"
                  >
                    {CATALOG_TYPE_LABELS[entry.type]}
                  </Tag>
                </Cell>
                {entry.row?.kind === "alias" ? (
                  <Cell colSpan={2} align="right" className="comet-body-s">
                    <span className="truncate">
                      Priced like{" "}
                      <span className="comet-code">
                        {describeAliasTarget(entry.row)}
                      </span>
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
                <Cell>
                  <MoreFields entry={entry} />
                </Cell>
                <Cell align="right" className="comet-body-s text-muted-slate">
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
