import React from "react";
import filter from "lodash/filter";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import HeaderStatistic from "@/components/shared/DataTableHeaders/HeaderStatistic";
import { cn } from "@/lib/utils";
import { CELL_HORIZONTAL_ALIGNMENT_MAP } from "@/constants/shared";
import { STATISTIC_AGGREGATION_TYPE } from "@/types/shared";

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
    // Find all statistics matching the key (could have both AVG and PERCENTAGE)
    const matchingStatistics = filter(
      columnsStatistic,
      (s) => s.name === statisticKey,
    );

    // Find AVG and PERCENTAGE statistics separately
    const avgStatistic = matchingStatistics.find(
      (s) => s.type === STATISTIC_AGGREGATION_TYPE.AVG,
    );
    const percentileStatistic = matchingStatistics.find(
      (s) => s.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE,
    );

    // Use AVG if available, otherwise use the first matching statistic
    const columnStatistic = avgStatistic ?? matchingStatistics[0];

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
            percentileStatistic={percentileStatistic}
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
