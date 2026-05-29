import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import { Explainer, ROW_HEIGHT } from "@/types/shared";
import { formatNumericData, toString } from "@/lib/utils";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import {
  EMPTY_CELL_PLACEHOLDER,
  isCellValueEmpty,
} from "@/shared/DataTableCells/EmptyCellPlaceholder";
import CellTooltipWrapper from "@/shared/DataTableCells/CellTooltipWrapper";
import { getAggregatedCellValue } from "@/shared/DataTableCells/aggregationUtils";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";

const TextCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isLarge = rowHeight === ROW_HEIGHT.large;
  const isEmpty = isCellValueEmpty(value);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isLarge ? (
        <div className="h-full overflow-y-auto whitespace-pre-wrap break-words">
          {isEmpty ? (
            EMPTY_CELL_PLACEHOLDER
          ) : (
            <LinkifyText>{value}</LinkifyText>
          )}
        </div>
      ) : (
        <CellTooltipWrapper content={toString(value)}>
          <span className="truncate">
            {isEmpty ? (
              EMPTY_CELL_PLACEHOLDER
            ) : (
              <LinkifyText>{value}</LinkifyText>
            )}
          </span>
        </CellTooltipWrapper>
      )}
    </CellWrapper>
  );
};

type CustomMeta = {
  aggregationKey?: string;
  dataFormatter?: (value: number) => string;
};

const TextAggregationCell = <TData,>(context: CellContext<TData, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, dataFormatter = formatNumericData } = (custom ??
    {}) as CustomMeta;

  const { value } = getAggregatedCellValue(
    context,
    aggregationKey,
    dataFormatter,
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={value}>
        <span className="truncate text-light-slate">{value}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

TextCell.Aggregation = TextAggregationCell;

type GroupCustomMeta = {
  valueKey: string;
  labelKey: string;
  countAggregationKey?: string;
  explainer?: Explainer;
};

const GroupTextCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const { valueKey, labelKey, countAggregationKey, explainer } = (custom ??
    {}) as GroupCustomMeta;
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

TextCell.Group = GroupTextCell;

export default TextCell;
