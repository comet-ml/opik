import React from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
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
};

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
      <span className="comet-body-s rounded px-1.5 py-0.5 hover:bg-primary-100">
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
}) => {
  const SortIcon = sortDirection === "asc" ? ArrowUp : ArrowDown;
  return (
    <div className="overflow-x-auto rounded-md border">
      <Table className="min-w-[960px]">
        <TableHeader>
          <TableRow>
            <TableHead className="h-10 w-[30%] px-4">
              <button
                type="button"
                className="comet-body-s-accented inline-flex items-center gap-1 text-foreground"
                onClick={onToggleSort}
                aria-label={`Sort by model, ${
                  sortDirection === "asc" ? "A to Z" : "Z to A"
                }`}
              >
                Model
                <SortIcon className="size-3.5" />
              </button>
            </TableHead>
            <TableHead className="h-10 w-[13%] px-4">Provider</TableHead>
            <TableHead className="h-10 w-[9%] px-4">Type</TableHead>
            <TableHead className="h-10 w-[12%] px-4 text-right">
              Input
            </TableHead>
            <TableHead className="h-10 w-[12%] px-4 text-right">
              Output
            </TableHead>
            <TableHead className="h-10 w-[10%] px-4">More</TableHead>
            <TableHead className="h-10 w-[14%] px-4 text-right">
              Last updated
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((entry) => (
            <TableRow
              key={entry.key}
              className="cursor-pointer"
              onClick={() => onOpen(entry)}
              onKeyDown={(event) => event.key === "Enter" && onOpen(entry)}
              tabIndex={0}
              data-testid="model-price-row"
            >
              <Cell>
                <span className="comet-code truncate">{entry.model}</span>
              </Cell>
              <Cell className="comet-body-s">
                {entry.provider || (
                  <span className="text-light-slate">Any provider</span>
                )}
              </Cell>
              <Cell>
                <Tag variant={TYPE_TAG_VARIANT[entry.type]} size="sm">
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
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default CatalogTable;
