import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import { toString } from "@/lib/utils";

const AutodetectCell = <TData,>(context: CellContext<TData, unknown>) => {
  const value = context.getValue();

  if (isObject(value)) {
    const codeContext = {
      ...context,
      getValue: () => JSON.stringify(value, null, 2),
    } as CellContext<unknown, unknown>;

    return <CodeCell {...codeContext} />;
  }

  const textContext = {
    ...context,
    getValue: () =>
      toString(value as string | number | boolean | null | undefined),
  } as CellContext<TData, string>;
  return <TextCell {...textContext} />;
};

export default AutodetectCell;
