import React from "react";
import find from "lodash/find";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import { COLUMN_TYPE } from "@/types/shared";
import HeaderStatistic from "@/components/shared/DataTableHeaders/HeaderStatistic";
import { cn } from "@/lib/utils";

type CellWrapperProps = {
  children?: React.ReactNode;
  metadata?: ColumnMeta<unknown, unknown>;
  tableMetadata?: TableMeta<unknown>;
  className?: string;
  onClick?: (e: React.MouseEvent<HTMLDivElement>) => void;
};

const HeaderWrapper: React.FunctionComponent<CellWrapperProps> = ({
  children,
  metadata,
  tableMetadata,
  className,
  onClick,
}) => {
  const { type, statisticKey } = metadata || {};
  const { columnsStatistic } = tableMetadata || {};

  const horizontalAlignClass =
    type === COLUMN_TYPE.number ? "justify-end" : "justify-start";

  const heightClass = columnsStatistic ? "h-14" : "h-11";

  if (columnsStatistic) {
    const data = find(columnsStatistic, (s) => s.name === statisticKey);

    return (
      <div
        className={cn("flex flex-col py-2", heightClass, className)}
        onClick={onClick}
      >
        <div
          className={cn(
            "flex size-full items-center gap-1 px-3",
            horizontalAlignClass,
          )}
        >
          {children}
        </div>
        <div
          className={cn(
            "flex size-full items-center gap-1 px-3",
            horizontalAlignClass,
          )}
        >
          <HeaderStatistic statistic={data} />
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
    >
      {children}
    </div>
  );
};

export default HeaderWrapper;
