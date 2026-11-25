import React, { useMemo } from "react";
import { Link2 } from "lucide-react";
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
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import TextCell from "@/shared/DataTableCells/TextCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  generateActionsColumDef,
  generatePinColumDef,
} from "@/shared/DataTable/utils";
import PriceRowActionsCell, { PriceRowActions } from "./PriceRowActionsCell";
import { convertColumnDataToColumn } from "@/lib/table";
import { COLUMN_PIN_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  ANY_PROVIDER_LABEL,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  PRICE_FIELDS,
  PRICE_LABELS,
  PriceField,
  describeAliasTarget,
} from "@/lib/model-prices";
import {
  PRICE_UNIT_SUFFIX,
  TYPE_DESCRIPTIONS,
  TYPE_TAG_VARIANT,
  formatPrice,
} from "./format";

const NO_VALUE = "-";
const ICON_COLUMN_SIZE = 32;

const TypeCell = (context: CellContext<CatalogEntry, unknown>) => {
  const { type } = context.row.original;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <ColoredTag
        label={CATALOG_TYPE_LABELS[type]}
        variant={TYPE_TAG_VARIANT[type]}
        tooltip={TYPE_DESCRIPTIONS[type]}
        size={getCellTagSize(context, TAG_SIZE_MAP)}
      />
    </CellWrapper>
  );
};

const PriceCell = (context: CellContext<CatalogEntry, unknown>) => {
  const entry = context.row.original;
  const field = context.column.id as PriceField;

  if (entry.row?.kind === "alias") {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <TooltipWrapper
          content={`Priced like ${describeAliasTarget(entry.row)}`}
        >
          <span className="flex min-w-0 items-center gap-1 text-light-slate">
            <Link2 className="size-3 shrink-0" />
            <span className="truncate">
              {formatPrice(entry.aliasPrices?.[field]) ?? NO_VALUE}
            </span>
          </span>
        </TooltipWrapper>
      </CellWrapper>
    );
  }

  const text = formatPrice(entry.prices[field]) ?? NO_VALUE;
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
    cell: TypeCell as never,
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
  canEdit: boolean;
  onRemove: (entry: CatalogEntry) => void;
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
  canEdit,
  onRemove,
}) => {
  const columns = useMemo<ColumnDef<CatalogEntry>[]>(
    () => [
      // DataTable stretches every column to fill the row, so the icon-only
      // pin and actions columns start smaller to end up near 40 px.
      { ...generatePinColumDef<CatalogEntry>(), size: ICON_COLUMN_SIZE },
      ...convertColumnDataToColumn<CatalogEntry, CatalogEntry>([MODEL_COLUMN], {
        sortableColumns: ["model"],
      }),
      ...convertColumnDataToColumn<CatalogEntry, CatalogEntry>(PRICE_COLUMNS, {
        selectedColumns,
        columnsOrder,
      }),
      ...(canEdit
        ? [
            {
              ...generateActionsColumDef<CatalogEntry>({
                cell: PriceRowActionsCell as never,
                customMeta: { onRemove } satisfies PriceRowActions,
              }),
              size: ICON_COLUMN_SIZE,
            },
          ]
        : []),
    ],
    [selectedColumns, columnsOrder, canEdit, onRemove],
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
