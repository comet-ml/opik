import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import MediaCell from "@/components/shared/DataTableCells/MediaCell";
import { toString } from "@/lib/utils";
import { useMediaTypeDetection } from "@/hooks/useMediaTypeDetection";

const AutodetectCell = <TData,>(context: CellContext<TData, unknown>) => {
  const value = context.getValue();

  const { mediaData } = useMediaTypeDetection(value);

  if (mediaData) {
    const mediaContext = {
      ...context,
      getValue: () => mediaData,
    } as CellContext<TData, unknown>;

    return <MediaCell {...mediaContext} />;
  }

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
