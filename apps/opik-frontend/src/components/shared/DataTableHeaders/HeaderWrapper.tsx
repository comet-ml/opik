import React from "react";
import find from "lodash/find";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import HeaderStatistic from "@/components/shared/DataTableHeaders/HeaderStatistic";
import { cn } from "@/lib/utils";
import { CELL_HORIZONTAL_ALIGNMENT_MAP } from "@/constants/shared";

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
  const { type, statisticKey, statisticDataFormater } = metadata || {};
  const { columnsStatistic } = tableMetadata || {};

  const horizontalAlignClass =
    CELL_HORIZONTAL_ALIGNMENT_MAP[type!] ?? "justify-start";

  const heightClass = columnsStatistic ? "h-14" : "h-11";

  if (supportStatistic && columnsStatistic) {
    const columnStatistic = find(
      columnsStatistic,
      (s) => s.name === statisticKey,
    );

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
            statistic={columnStatistic}
            columnsStatistic={columnsStatistic}
            statisticKey={statisticKey}
            dataFormater={statisticDataFormater}
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
