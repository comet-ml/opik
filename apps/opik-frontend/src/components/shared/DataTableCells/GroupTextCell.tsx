import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { toString } from "@/lib/utils";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { Explainer } from "@/types/shared";

type CustomMeta = {
  valueKey: string;
  labelKey: string;
  countAggregationKey?: string;
  explainer?: Explainer;
};

const GroupTextCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const { valueKey, labelKey, countAggregationKey, explainer } = (custom ??
    {}) as CustomMeta;
  const label = get(cellData, labelKey.split("."), undefined);
  const value = get(cellData, valueKey.split("."), undefined);

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};
  const data = aggregationMap?.[rowId];
  const count =
    countAggregationKey && data
      ? get(data, countAggregationKey, undefined)
      : undefined;

  const hasValue = Boolean(label || value);

  const countText = isNumber(count) ? ` (${count})` : "";

  if (!hasValue) {
    const text = "Undefined";

    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <CellTooltipWrapper content={text}>
          <span className="truncate">
            {text}
            {countText}
          </span>
          {explainer && <ExplainerIcon {...explainer} className="ml-1" />}
        </CellTooltipWrapper>
      </CellWrapper>
    );
  }

  const textContext = {
    ...context,
    getValue: () =>
      toString(
        (label || value) as string | number | boolean | null | undefined,
      ) + countText,
  } as CellContext<TData, string>;
  return <TextCell {...textContext} />;
};

export default GroupTextCell;
