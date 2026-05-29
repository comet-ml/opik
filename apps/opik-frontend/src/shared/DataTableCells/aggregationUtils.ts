import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { EMPTY_CELL_PLACEHOLDER } from "@/shared/DataTableCells/EmptyCellPlaceholder";

/**
 * Resolves an aggregated cell value from the table's `aggregationMap` by key and
 * formats it, falling back to the empty placeholder for non-numeric values.
 * Returns the raw value (for tooltips / conditional rendering) alongside the
 * formatted display string so each aggregation cell can render it as needed.
 */
export const getAggregatedCellValue = <TData, TValue>(
  context: CellContext<TData, TValue>,
  aggregationKey: string | undefined,
  dataFormatter: (value: number) => string,
): { rawValue: unknown; value: string } => {
  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue = get(data, aggregationKey ?? "", undefined);
  const value = isNumber(rawValue)
    ? dataFormatter(rawValue)
    : EMPTY_CELL_PLACEHOLDER;

  return { rawValue, value };
};
