import React, { useMemo } from "react";
import {
  CellContext,
  ColumnDef,
  ColumnPinningState,
  ColumnSizingState,
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
import { generatePinColumDef } from "@/shared/DataTable/utils";
import { convertColumnDataToColumn } from "@/lib/table";
import { COLUMN_PIN_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  ANY_PROVIDER_LABEL,
  CATALOG_TYPES,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  PRICE_FIELDS,
  PRICE_LABELS,
  PriceField,
  describeAliasTarget,
} from "@/lib/model-prices";
import { PRICE_UNIT_SUFFIX, TYPE_TAG_VARIANT, formatPrice } from "./format";

const NO_VALUE = "-";
const PIN_COLUMN_SIZE = 32;

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

const MODEL_COLUMN: ColumnData<CatalogEntry> = {
  id: "model",
  label: "Model",
  type: COLUMN_TYPE.string,
  cell: TextCell as never,
  size: 240,
};

export const PRICE_COLUMNS: ColumnData<CatalogEntry>[] = [
  {
    id: "provider",
    label: "Provider",
    type: COLUMN_TYPE.string,
    accessorFn: (entry) => entry.provider || ANY_PROVIDER_LABEL,
    cell: TextCell as never,
    size: 140,
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.category,
    accessorFn: (entry) => CATALOG_TYPE_LABELS[entry.type],
    cell: TagCell as never,
    customMeta: { variantMap: TYPE_VARIANT_BY_LABEL },
    size: 100,
  },
  ...PRICE_FIELDS.map((field) => ({
    id: field,
    label: `${PRICE_LABELS[field]} ${PRICE_UNIT_SUFFIX}`,
    type: COLUMN_TYPE.cost,
    cell: PriceCell as never,
    size: 140,
  })),
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (entry) => entry.lastUpdatedAt,
    cell: TimeCell as never,
    size: 130,
  },
];

export const DEFAULT_SELECTED_COLUMNS = [
  "provider",
  "type",
  "input_price",
  "output_price",
  "last_updated_at",
];

export const DEFAULT_COLUMNS_ORDER = PRICE_COLUMNS.map((column) => column.id);

const COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_PIN_ID, "model"],
  right: [],
};

type PricesTableProps = {
  entries: CatalogEntry[];
  selectedColumns: string[];
  columnsOrder: string[];
  columnsWidth: ColumnSizingState;
  onColumnsWidthChange: OnChangeFn<ColumnSizingState>;
  sorting: ColumnSort[];
  onSortingChange: OnChangeFn<ColumnSort[]>;
  pinnedKeys: string[];
  onPinnedKeysChange: (keys: string[]) => void;
  activeKey?: string;
  onOpen: (entry: CatalogEntry) => void;
  onClearFilters: () => void;
};

const PricesTable: React.FC<PricesTableProps> = ({
  entries,
  selectedColumns,
  columnsOrder,
  columnsWidth,
  onColumnsWidthChange,
  sorting,
  onSortingChange,
  pinnedKeys,
  onPinnedKeysChange,
  activeKey,
  onOpen,
  onClearFilters,
}) => {
  const columns = useMemo<ColumnDef<CatalogEntry>[]>(
    () => [
      // DataTable stretches every column to fill the row, so the shared 40 px
      // pin column starts smaller here to end up near 40 px once stretched.
      { ...generatePinColumDef<CatalogEntry>(), size: PIN_COLUMN_SIZE },
      ...convertColumnDataToColumn<CatalogEntry, CatalogEntry>([MODEL_COLUMN], {
        sortableColumns: ["model"],
      }),
      ...convertColumnDataToColumn<CatalogEntry, CatalogEntry>(PRICE_COLUMNS, {
        selectedColumns,
        columnsOrder,
      }),
    ],
    [selectedColumns, columnsOrder],
  );

  const rowPinning = useMemo<RowPinningState>(
    () => ({ top: pinnedKeys, bottom: [] }),
    [pinnedKeys],
  );
  const setRowPinning: OnChangeFn<RowPinningState> = (updater) =>
    onPinnedKeysChange(functionalUpdate(updater, rowPinning).top ?? []);

  return (
    <DataTable
      columns={columns}
      data={entries}
      getRowId={(entry) => entry.key}
      onRowClick={onOpen}
      activeRowId={activeKey}
      sortConfig={{ enabled: true, sorting, setSorting: onSortingChange }}
      resizeConfig={{
        enabled: true,
        columnSizing: columnsWidth,
        onColumnResize: onColumnsWidthChange,
      }}
      pinningConfig={{ rowPinning, setRowPinning }}
      columnPinning={COLUMN_PINNING}
      noData={<DataTableNoMatchingData onClearFilters={onClearFilters} />}
    />
  );
};

export default PricesTable;
