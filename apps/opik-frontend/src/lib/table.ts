import { ColumnData } from "@/types/shared";
import { ColumnDef } from "@tanstack/react-table";
import { TypeHeader } from "@/components/shared/DataTableHeaders/TypeHeader";
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

export const convertColumnDataToColumn = <TColumnData, TData>(
  columns: ColumnData<TColumnData>[],
  {
    columnsOrder = [],
    selectedColumns,
    columnsWidth = {},
  }: {
    columnsOrder?: string[];
    selectedColumns?: string[];
    columnsWidth?: Record<string, number>;
  },
) => {
  const retVal: ColumnDef<TData>[] = [];

  sortColumnsByOrder(columns, columnsOrder).forEach((column) => {
    const isSelected = selectedColumns
      ? selectedColumns.includes(column.id)
      : true;
    if (isSelected) {
      const size = columnsWidth[column.id] ?? column.size;
      retVal.push({
        ...(column.accessorFn && { accessorFn: column.accessorFn }),
        accessorKey: column.id,
        header: TypeHeader,
        meta: {
          type: column.type,
          header: column.label,
          iconType: column.iconType,
          ...(column.verticalAlignment && {
            verticalAlignment: column.verticalAlignment,
          }),
          ...(column.customMeta && { custom: column.customMeta }),
        },
        ...(size && { size }),
        cell: (column.cell ?? TextCell) as never,
        enableSorting: column.sortable || false,
      });
    }
  });

  return retVal;
};
