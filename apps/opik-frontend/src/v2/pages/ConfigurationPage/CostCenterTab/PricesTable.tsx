import React, { useMemo } from "react";
import {
  CellContext,
  ColumnDef,
  ColumnPinningState,
  ColumnSort,
  OnChangeFn,
  RowPinningState,
  functionalUpdate,
} from "@tanstack/react-table";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/shared/DataTableCells/CellTooltipWrapper";
import TagCell from "@/shared/DataTableCells/TagCell";
import TextCell from "@/shared/DataTableCells/TextCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { generatePinColumDef } from "@/shared/DataTable/utils";
import { convertColumnDataToColumn } from "@/lib/table";
import { COLUMN_PIN_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  ANY_PROVIDER_LABEL,
  CATALOG_TYPES,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  PRICE_LABELS,
  PriceField,
  describeAliasTarget,
  extraFields,
} from "@/lib/model-prices";
import { PRICE_UNIT_SUFFIX, TYPE_TAG_VARIANT, formatPrice } from "./format";

const NO_VALUE = "-";

const TYPE_VARIANT_BY_LABEL = Object.fromEntries(
  CATALOG_TYPES.map((type) => [
    CATALOG_TYPE_LABELS[type],
    TYPE_TAG_VARIANT[type],
  ]),
);

const PriceCell = (context: CellContext<CatalogEntry, unknown>) => {
  const entry = context.row.original;
  const field = context.column.id as PriceField;
  const text =
    entry.row?.kind === "alias"
      ? field === "input_price"
        ? `Priced like ${describeAliasTarget(entry.row)}`
        : NO_VALUE
      : (formatPrice(entry.prices[field]) ?? NO_VALUE);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={text}>
        <span className="truncate">{text}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

const ExtraPricesCell = (context: CellContext<CatalogEntry, unknown>) => {
  const entry = context.row.original;
  const fields = extraFields(entry);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {fields.length ? (
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
          <span className="truncate">
            +{fields.length} {fields.length === 1 ? "field" : "fields"}
          </span>
        </TooltipWrapper>
      ) : (
        NO_VALUE
      )}
    </CellWrapper>
  );
};

const COLUMNS: ColumnData<CatalogEntry>[] = [
  {
    id: "model",
    label: "Model",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    size: 320,
  },
  {
    id: "provider",
    label: "Provider",
    type: COLUMN_TYPE.string,
    accessorFn: (entry) => entry.provider || ANY_PROVIDER_LABEL,
    cell: TextCell as never,
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.category,
    accessorFn: (entry) => CATALOG_TYPE_LABELS[entry.type],
    cell: TagCell as never,
    customMeta: { variantMap: TYPE_VARIANT_BY_LABEL },
  },
  {
    id: "input_price",
    label: `Input ${PRICE_UNIT_SUFFIX}`,
    type: COLUMN_TYPE.cost,
    cell: PriceCell as never,
  },
  {
    id: "output_price",
    label: `Output ${PRICE_UNIT_SUFFIX}`,
    type: COLUMN_TYPE.cost,
    cell: PriceCell as never,
  },
  {
    id: "extra_prices",
    label: "More",
    type: COLUMN_TYPE.string,
    cell: ExtraPricesCell as never,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (entry) => entry.lastUpdatedAt,
    cell: TimeCell as never,
  },
];

const COLUMN_DEFS: ColumnDef<CatalogEntry>[] = [
  generatePinColumDef<CatalogEntry>(),
  ...convertColumnDataToColumn<CatalogEntry, CatalogEntry>(COLUMNS, {
    sortableColumns: ["model"],
  }),
];

const COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_PIN_ID, "model"],
  right: [],
};

type PricesTableProps = {
  entries: CatalogEntry[];
  sortDirection: "asc" | "desc";
  onSortDirectionChange: (direction: "asc" | "desc") => void;
  pinnedKeys: string[];
  onPinnedKeysChange: (keys: string[]) => void;
  activeKey?: string;
  onOpen: (entry: CatalogEntry) => void;
  onClearFilters: () => void;
};

const PricesTable: React.FC<PricesTableProps> = ({
  entries,
  sortDirection,
  onSortDirectionChange,
  pinnedKeys,
  onPinnedKeysChange,
  activeKey,
  onOpen,
  onClearFilters,
}) => {
  const sorting = useMemo<ColumnSort[]>(
    () => [{ id: "model", desc: sortDirection === "desc" }],
    [sortDirection],
  );
  const setSorting: OnChangeFn<ColumnSort[]> = (updater) =>
    onSortDirectionChange(
      functionalUpdate(updater, sorting)[0]?.desc ? "desc" : "asc",
    );

  const rowPinning = useMemo<RowPinningState>(
    () => ({ top: pinnedKeys, bottom: [] }),
    [pinnedKeys],
  );
  const setRowPinning: OnChangeFn<RowPinningState> = (updater) =>
    onPinnedKeysChange(functionalUpdate(updater, rowPinning).top ?? []);

  return (
    <DataTable
      columns={COLUMN_DEFS}
      data={entries}
      getRowId={(entry) => entry.key}
      onRowClick={onOpen}
      activeRowId={activeKey}
      sortConfig={{ enabled: true, sorting, setSorting }}
      pinningConfig={{ rowPinning, setRowPinning }}
      columnPinning={COLUMN_PINNING}
      noData={<DataTableNoMatchingData onClearFilters={onClearFilters} />}
    />
  );
};

export default PricesTable;
