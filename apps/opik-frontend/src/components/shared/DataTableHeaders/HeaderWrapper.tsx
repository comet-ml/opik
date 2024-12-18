import React from "react";
import find from "lodash/find";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import HeaderStatistic from "@/components/shared/DataTableHeaders/HeaderStatistic";
import { cn } from "@/lib/utils";
import { CELL_HORIZONTAL_ALIGNMENT_MAP } from "@/constants/shared";

type HeaderWrapperProps = {
  children?: React.ReactNode;
  metadata?: ColumnMeta<unknown, unknown>;
  tableMetadata?: TableMeta<unknown>;
  className?: string;
  onClick?: (e: React.MouseEvent<HTMLDivElement>) => void;
  supportStatistic?: boolean;
};

const HeaderWrapper: React.FunctionComponent<HeaderWrapperProps> = ({
  children,
  metadata,
  tableMetadata,
  className,
  onClick,
  supportStatistic = true,
}) => {
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
        >
          <HeaderStatistic
            statistic={columnStatistic}
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
