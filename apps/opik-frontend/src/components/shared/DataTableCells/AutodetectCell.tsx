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
  type ProcessedRow = {
    data?: Record<string, unknown>;
    __processedImages?: ReturnType<typeof processInputData>;
  };

  const row = context.row.original as ProcessedRow | undefined;

  const rowImages = (() => {
    if (!row || !isPlainObject(row)) {
      return [] as ReturnType<typeof processInputData>["images"];
    }

    if (!row.__processedImages) {
      const data = row.data ?? {};
      row.__processedImages = processInputData(data);
    }

    return row.__processedImages?.images ?? [];
  })();

  const valueImages = getImagesFromValue(value);

  const placeholderLabel =
    typeof value === "string" && /\[image.*\]/i.test(value) ? value : undefined;

  const combinedImages = [...valueImages];

  if (typeof value === "string") {
    const markdownRegex = /!\[image\]\((.+?)\)/gi;
    let match: RegExpExecArray | null;
    while ((match = markdownRegex.exec(value))) {
      const url = match[1];
      if (url && !combinedImages.find((image) => image.url === url)) {
        combinedImages.push({
          url,
          name: placeholderLabel ?? "Markdown image",
        });
      }
    }
  }

  if (rowImages.length && (!placeholderLabel || combinedImages.length === 0)) {
    const matches = placeholderLabel
      ? rowImages.filter((image) => {
          if (!image.name) return false;
          if (image.name === placeholderLabel) return true;
          return image.name.endsWith(placeholderLabel);
        })
      : rowImages;

    matches.forEach((image) => {
      if (!combinedImages.find((item) => item.url === image.url)) {
        combinedImages.push(image);
      }
    });
  }

  if (combinedImages.length > 0) {
    const fallbackText =
      typeof value === "string"
        ? /!\[image\]/i.test(value) || /\[image.*\]/i.test(value)
          ? "Image"
          : value
        : combinedImages[0]?.name ?? "";

    return (
      <ImagePreviewCell
        context={context}
        images={combinedImages}
        fallbackText={fallbackText}
        placeholderLabel={placeholderLabel}
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
