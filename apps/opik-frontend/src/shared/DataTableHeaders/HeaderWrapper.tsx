import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import HeaderStatistic from "@/shared/DataTableHeaders/HeaderStatistic";
import { cn } from "@/lib/utils";
import {
  CELL_HORIZONTAL_ALIGNMENT_MAP,
  HEADER_TEXT_CLASS_MAP,
} from "@/constants/shared";
import { COLUMN_TYPE, ROW_HEIGHT } from "@/types/shared";

type CustomColumnMeta = {
  type?: COLUMN_TYPE;
  statisticKey?: string;
  statisticDataFormater?: (value: number) => string;
  statisticTooltipFormater?: (value: number) => string;
  supportsPercentiles?: boolean;
};

type HeaderWrapperProps<TData> = {
  children?: React.ReactNode;
  metadata?: ColumnMeta<TData, unknown>;
  tableMetadata?: TableMeta<TData>;
  className?: string;
  onClick?: (e: React.MouseEvent<HTMLDivElement>) => void;
  supportStatistic?: boolean;
};

const HeaderWrapper = <TData,>({
  children,
  metadata,
  tableMetadata,
  className,
  onClick,
  supportStatistic = true,
}: HeaderWrapperProps<TData>) => {
  const metaData = metadata as CustomColumnMeta | undefined;
  const type = metaData?.type;
  const statisticKey = metaData?.statisticKey;
  const statisticDataFormater = metaData?.statisticDataFormater;
  const statisticTooltipFormater = metaData?.statisticTooltipFormater;
  const supportsPercentiles = metaData?.supportsPercentiles;
  const { columnsStatistic, rowHeight } = tableMetadata || {};

  const horizontalAlignClass =
    CELL_HORIZONTAL_ALIGNMENT_MAP[type!] ?? "justify-start";

  const isSmall = rowHeight === ROW_HEIGHT.small;
  const hasStats = supportStatistic && columnsStatistic;

  const nameClass = HEADER_TEXT_CLASS_MAP[rowHeight ?? ROW_HEIGHT.small];

  if (hasStats) {
    return (
      <div
        className={cn(
          "flex flex-col justify-center px-3",
          isSmall ? "h-11" : "h-12",
          className,
        )}
        onClick={onClick}
        data-header-wrapper="true"
      >
        <div
          className={cn(
            "flex items-center gap-1",
            nameClass,
            horizontalAlignClass,
          )}
        >
          {children}
        </div>
        <div
          className={cn(
            "comet-body-xs flex items-center gap-1",
            horizontalAlignClass,
          )}
          onClick={(e) => e.stopPropagation()}
        >
          <HeaderStatistic
            columnsStatistic={columnsStatistic}
            statisticKey={statisticKey}
            dataFormater={statisticDataFormater}
            tooltipFormater={statisticTooltipFormater}
            supportsPercentiles={supportsPercentiles}
          />
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        "flex size-full items-center gap-1 px-3",
        nameClass,
        isSmall ? "h-8" : "h-11",
        horizontalAlignClass,
        className,
      )}
      onClick={onClick}
      data-header-wrapper="true"
    >
      {children}
    </div>
  );
};

export default HeaderWrapper;
