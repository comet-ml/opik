import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import HeaderStatistic from "@/components/shared/DataTableHeaders/HeaderStatistic";
import { cn } from "@/lib/utils";
import { CELL_HORIZONTAL_ALIGNMENT_MAP } from "@/constants/shared";
import { COLUMN_TYPE } from "@/types/shared";

type CustomColumnMeta = {
  type?: COLUMN_TYPE;
  statisticKey?: string;
  statisticDataFormater?: (value: number) => string;
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
  const supportsPercentiles = metaData?.supportsPercentiles;
  const { columnsStatistic } = tableMetadata || {};

  const horizontalAlignClass =
    CELL_HORIZONTAL_ALIGNMENT_MAP[type!] ?? "justify-start";

  const heightClass = columnsStatistic ? "h-14" : "h-11";

  if (supportStatistic && columnsStatistic) {
    return (
      <div
        className={cn("flex flex-col py-2 px-3", heightClass, className)}
        onClick={onClick}
        data-header-wrapper="true"
      >
        <div
          className={cn(
            "flex size-full items-center gap-1",
            horizontalAlignClass,
          )}
        >
          {children}
        </div>
        <div
          className={cn(
            "flex size-full items-center gap-1",
            horizontalAlignClass,
          )}
          onClick={(e) => e.stopPropagation()}
        >
          <HeaderStatistic
            columnsStatistic={columnsStatistic}
            statisticKey={statisticKey}
            dataFormater={statisticDataFormater}
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
        heightClass,
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
