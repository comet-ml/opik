import { ColumnData, COLUMN_NAME_ID, COLUMN_TYPE } from "@/types/shared";
import { ColumnDef } from "@tanstack/react-table";
import TypeHeader from "@/components/shared/DataTableHeaders/TypeHeader";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import {
  DEFAULT_COLUMN_WIDTH,
  DEFAULT_NUMERIC_COLUMN_WIDTH,
  NAME_COLUMN_WIDTH,
} from "@/constants/shared";

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

export const isColumnSortable = (id: string, sortableColumns: string[]) => {
  if (sortableColumns.includes(id)) return true;

  const keys = id.split(".");

  return keys.length > 1 ? sortableColumns.includes(`${keys[0]}.*`) : false;
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
      if (
        Boolean(sortableColumns?.length) &&
        isColumnSortable(column.id, sortableColumns)
      ) {
        retVal.push(
          mapColumnDataFields({
            ...column,
            sortable: true,
          }),
        );
      } else {
        retVal.push(mapColumnDataFields(column));
      }
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
    size: (() => {
      if (columnData.size) return columnData.size;
      if (columnData.id === COLUMN_NAME_ID) return NAME_COLUMN_WIDTH;
      if (
        columnData.type &&
        [COLUMN_TYPE.number, COLUMN_TYPE.cost, COLUMN_TYPE.duration].includes(
          columnData.type,
        )
      ) {
        return DEFAULT_NUMERIC_COLUMN_WIDTH;
      }
      return DEFAULT_COLUMN_WIDTH;
    })(),
    cell: (columnData.cell ?? TextCell) as never,
    enableSorting: columnData.sortable || false,
  };
};
