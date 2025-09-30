import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import JsonView from "react18-json-view";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";
import { processInputData } from "@/lib/images";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { cn } from "@/lib/utils";
import { ParsedImageData } from "@/types/attachments";

interface CustomMeta {
  showIndex: boolean;
}

const PlaygroundVariableCell: React.FunctionComponent<
  CellContext<never, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const rowIndex = context.row.index;

  const value = context.getValue() as string;
  const jsonViewTheme = useJsonViewTheme();

  const { showIndex } = (custom ?? {}) as CustomMeta;

  const images = useMemo(() => {
    if (typeof value !== "string" || value.trim().length === 0) {
      return [] as ParsedImageData[];
    }

    try {
      return processInputData({ value }).images;
    } catch (error) {
      if (import.meta.env.DEV) {
        // eslint-disable-next-line no-console
        console.debug("[Playground] Failed to extract images", error);
      }
      return [] as ParsedImageData[];
    }
  }, [value]);

  const getContent = () => {
    if (images.length > 0) {
      const [firstImage, ...restImages] = images;
      const remainingCount = restImages.length;
      const fallbackText = firstImage?.name ?? "Image";
      const label =
        fallbackText.length > 60
          ? `${fallbackText.slice(0, 57)}…`
          : fallbackText;

      return (
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
      );
    }

    if (isObject(value)) {
      return (
        <div className="size-full overflow-y-auto overflow-x-hidden whitespace-normal">
          <JsonView
            src={value}
            {...jsonViewTheme}
            collapseStringsAfterLength={10000}
          />
        </div>
      );
    }

    return <div className="size-full overflow-y-auto">{value}</div>;
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="pt-5"
    >
      <div className="size-full pl-1">
        <div className="h-[var(--cell-top-height)] items-center font-semibold">
          {showIndex ? rowIndex + 1 : ""}
        </div>
        <div className="h-[calc(100%-var(--cell-top-height))]">
          {getContent()}
        </div>
      </div>
    </CellWrapper>
  );
};

export default PlaygroundVariableCell;
