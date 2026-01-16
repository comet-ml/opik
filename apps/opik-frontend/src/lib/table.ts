import { ColumnData } from "@/types/shared";
import { ColumnDef } from "@tanstack/react-table";
import TypeHeader from "@/components/shared/DataTableHeaders/TypeHeader";
import TextCell from "@/components/shared/DataTableCells/TextCell";

export const sortColumnsByOrder = <TColumnData>(
  columns: ColumnData<TColumnData>[],
  order?: string[],
) => {
  if (!order || order.length === 0) return columns;

  const orderMap = order.reduce<Record<string, number>>((acc, id, index) => {
    acc[id] = index;
    return acc;
  }, {});

  return columns
    .slice()
    .sort((c1, c2) => (orderMap[c1.id] ?? 0) - (orderMap[c2.id] ?? 0));
};

export const hasAnyVisibleColumns = <TColumnData>(
  columns: ColumnData<TColumnData>[],
  selectedColumns: string[],
) => columns.some(({ id }) => selectedColumns.includes(id));

/**
 * Determines if a column can be sorted based on the backend's sortable_by response.
 * Handles multiple matching patterns:
 * 1. Direct match: column id exactly matches a sortable field
 * 2. Wildcard match: "data.*" allows sorting any field under "data." prefix
 * 3. Legacy single field: fields without dots that match wildcard patterns
 * 4. Prefix match: "output.field" matches if "output" is sortable
 *
 * @param id - The column identifier (e.g., "id", "data.field", "output.result")
 * @param sortableColumns - Array of sortable field patterns from backend
 * @returns true if the column can be sorted
 */
export const isColumnSortable = (id: string, sortableColumns: string[]) => {
  // Direct match: exact field name is sortable
  if (sortableColumns.includes(id)) return true;

  const keys = id.split(".");

  // Wildcard pattern match: e.g., "data.*" allows "data.field"
  if (keys.length > 1 && sortableColumns.includes(`${keys[0]}.*`)) {
    return true;
  }

  if (keys.length > 1 && sortableColumns.includes(keys[0])) {
    return true;
  }

  return false;
};

export const convertColumnDataToColumn = <TColumnData, TData>(
  columns: ColumnData<TColumnData>[],
  {
    columnsOrder = [],
    selectedColumns,
    sortableColumns = [],
  }: {
    columnsOrder?: string[];
    selectedColumns?: string[];
    sortableColumns?: string[];
  },
) => {
  const retVal: ColumnDef<TData>[] = [];

  sortColumnsByOrder(columns, columnsOrder).forEach((column) => {
    const isSelected = selectedColumns
      ? selectedColumns.includes(column.id)
      : true;
    if (isSelected) {
      // If column explicitly sets sortable to false, respect that
      // Otherwise, check if backend supports sorting
      const shouldEnableSorting =
        column.sortable !== false &&
        Boolean(sortableColumns?.length) &&
        isColumnSortable(column.id, sortableColumns);

      retVal.push(
        mapColumnDataFields({
          ...column,
          sortable: shouldEnableSorting,
        }),
      );
    }
  });

  return retVal;
};

export const mapColumnDataFields = <TColumnData, TData>(
  columnData: ColumnData<TColumnData>,
): ColumnDef<TData> => {
  return {
    ...(columnData.accessorFn && { accessorFn: columnData.accessorFn }),
    accessorKey: columnData.id,
    header: (columnData.header ?? TypeHeader) as never,
    meta: {
      type: columnData.type,
      header: columnData.label,
      headerCheckbox: columnData.headerCheckbox,
      iconType: columnData.iconType,
      statisticKey: columnData.statisticKey || columnData.id,
      ...(columnData.statisticDataFormater && {
        statisticDataFormater: columnData.statisticDataFormater,
      }),
      ...(columnData.supportsPercentiles !== undefined && {
        supportsPercentiles: columnData.supportsPercentiles,
      }),
      ...(columnData.verticalAlignment && {
        verticalAlignment: columnData.verticalAlignment,
      }),
      ...(columnData.overrideRowHeight && {
        overrideRowHeight: columnData.overrideRowHeight,
      }),
      ...(columnData.explainer && {
        explainer: columnData.explainer,
      }),
      ...(columnData.customMeta && { custom: columnData.customMeta }),
    },
    ...(columnData.size && { size: columnData.size }),
    ...(columnData.minSize && { minSize: columnData.minSize }),
    cell: (columnData.cell ?? TextCell) as never,
    ...(columnData.aggregatedCell && {
      aggregatedCell: columnData.aggregatedCell as never,
    }),
    enableSorting: columnData.sortable || false,
  };
};
