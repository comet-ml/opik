import { CellContext } from "@tanstack/react-table";
import isPlainObject from "lodash/isPlainObject";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import { toString } from "@/lib/utils";
import { isImageBase64String, processInputData } from "@/lib/images";
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
  const row = context.row.original as { data?: unknown } | undefined;

  const rowImages = (() => {
    if (!row || !isPlainObject(row)) {
      return [] as ReturnType<typeof processInputData>["images"];
    }

    const rowData = isPlainObject(row.data) ? (row.data as object) : {};

    return processInputData(rowData).images ?? [];
  })();

  const valueImages = getImagesFromValue(value);

  const placeholderLabel =
    typeof value === "string" && /\[image.*\]/i.test(value) ? value : undefined;

  const combinedImages = [...valueImages];

  let foundMarkdown = false;
  const markdownRegex = typeof value === "string" ? /!\[image\]\((.+?)\)/gi : null;
  if (markdownRegex) {
    const stringValue = value as string;
    let match: RegExpExecArray | null;
    while ((match = markdownRegex.exec(stringValue))) {
      const url = match[1];
      if (url && !combinedImages.find((image) => image.url === url)) {
        combinedImages.push({
          url,
          name: placeholderLabel ?? "Markdown image",
        });
      }
      foundMarkdown = true;
    }
  }

  const looksLikeImageString =
    typeof value === "string" &&
    (foundMarkdown ||
      /\.\w{1,6}(\?|$)/i.test(value) ||
      /^data:image\//i.test(value));

  const isBase64String =
    typeof value === "string" &&
    (value.startsWith("data:image/") || isImageBase64String(value));

  const shouldMergeRowImages =
    (placeholderLabel !== undefined || combinedImages.length > 0 || looksLikeImageString || isBase64String) &&
    rowImages.length > 0;

  if (shouldMergeRowImages && rowImages.length) {
    const matches = placeholderLabel
      ? rowImages.filter((image) => {
          if (!image.name) return false;
          if (image.name === placeholderLabel) return true;
          if (placeholderLabel && image.name.endsWith(placeholderLabel)) {
            return true;
          }
          return false;
        })
      : rowImages;

    matches.forEach((image) => {
      if (!combinedImages.find((item) => item.url === image.url)) {
        combinedImages.push(image);
      }
    });
  }

  if (combinedImages.length > 0) {
    const isImagePlaceholder =
      typeof value === "string" &&
      (/[\[]image.*?\]/i.test(value) || /!\[image\]/i.test(value));

    const fallbackText =
      typeof value === "string"
        ? isImagePlaceholder || looksLikeImageString || isBase64String
          ? combinedImages[0]?.name ?? "Image"
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
