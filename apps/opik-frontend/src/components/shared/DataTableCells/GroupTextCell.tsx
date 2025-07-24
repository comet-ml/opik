import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { toString } from "@/lib/utils";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

type CustomMeta = {
  valueKey: string;
  labelKey: string;
  countKey?: string;
};

const GroupTextCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const { valueKey, labelKey, countKey } = (custom ?? {}) as CustomMeta;
  const label = get(cellData, labelKey, undefined);
  const value = get(cellData, valueKey, undefined);
  const count = countKey ? get(cellData, countKey, undefined) : undefined;

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
          <ExplainerIcon
            description="Some of the experiments didn’t match any group."
            className="ml-1"
          />
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
