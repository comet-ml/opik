import { CellContext } from "@tanstack/react-table";
import isPlainObject from "lodash/isPlainObject";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import { toString } from "@/lib/utils";
import { processInputData } from "@/lib/images";
import ImagePreviewCell from "@/components/shared/DataTableCells/ImagePreviewCell";

const getImagesFromValue = (value: unknown) => {
  if (value === null || value === undefined) {
    return [];
  }

  if (typeof value === "string") {
    return processInputData({ value }).images;
  }

  if (Array.isArray(value) || isPlainObject(value)) {
    try {
      return processInputData(value as object).images;
    } catch (error) {
      return [];
    }
  }

  return [];
};

const AutodetectCell = <TData,>(context: CellContext<TData, unknown>) => {
  const value = context.getValue();

  const images = getImagesFromValue(value);

  if (images.length > 0) {
    const fallbackText =
      typeof value === "string" ? value : images[0]?.name ?? "";

    return (
      <ImagePreviewCell
        context={context}
        images={images}
        fallbackText={fallbackText}
      />
    );
  }

  if (isPlainObject(value) || Array.isArray(value)) {
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
