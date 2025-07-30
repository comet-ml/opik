import { CellContext } from "@tanstack/react-table";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import { toString } from "@/lib/utils";
import get from "lodash/get";

type CustomMeta = {
  valueKey: string;
  labelKey: string;
  defaultValue?: string;
};

const GroupTextCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const { valueKey, labelKey, defaultValue } = (custom ?? {}) as CustomMeta;
  const label = get(cellData, labelKey, undefined);
  const value = get(cellData, valueKey, undefined);

  const textContext = {
    ...context,
    getValue: () =>
      toString(
        (label || value || defaultValue) as
          | string
          | number
          | boolean
          | null
          | undefined,
      ),
  } as CellContext<TData, string>;
  return <TextCell {...textContext} />;
};

export default GroupTextCell;
