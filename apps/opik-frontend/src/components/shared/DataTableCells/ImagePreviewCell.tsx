import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ParsedImageData } from "@/types/attachments";
import { cn } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";

type ImagePreviewCellProps<TData> = {
  context: CellContext<TData, unknown>;
  images: ParsedImageData[];
  fallbackText?: string;
  placeholderLabel?: string;
};

const ImagePreviewCell = <TData,>({
  context,
  images,
  fallbackText,
  placeholderLabel,
}: ImagePreviewCellProps<TData>) => {
  if (!images.length) {
    return null;
  }

  const matchedImage = placeholderLabel
    ? images.find((image) => {
        if (!image.name) return false;
        if (image.name === placeholderLabel) return true;
        return image.name.endsWith(placeholderLabel);
      })
    : undefined;

  const [firstImage, ...restImages] = matchedImage ? [matchedImage] : images;
  const remainingCount = restImages.length;
  const fullLabel = fallbackText || firstImage?.name || "";
  const label =
    fullLabel.length > 60 ? `${fullLabel.slice(0, 57)}…` : fullLabel;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      stopClickPropagation
    >
      <TooltipWrapper
        content={
          <div className="max-h-80 max-w-[320px] overflow-y-auto">
            <ImagesListWrapper images={images} />
          </div>
        }
        stopClickPropagation
      >
        <div className="flex items-center gap-3">
          <div className="relative inline-flex">
            <img
              src={firstImage.url}
              alt={firstImage.name}
              className="size-10 rounded-md border border-border object-cover"
            />
            {remainingCount > 0 ? (
              <span className="absolute -bottom-1 -right-1 rounded-full bg-background px-1 text-xs font-medium text-foreground shadow-sm">
                +{remainingCount}
              </span>
            ) : null}
          </div>
          {label ? (
            <span className={cn("truncate text-xs text-muted-slate")}>
              {label}
            </span>
          ) : null}
        </div>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default ImagePreviewCell;
