import React from "react";
import isUndefined from "lodash/isUndefined";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import { toString } from "@/lib/utils";

export type CompareFiledValue = string | number | undefined | null;

export type CompareConfig = {
  name: string;
  data: Record<string, CompareFiledValue>;
  base: string;
  different: boolean;
};

type CustomMeta = {
  onlyDiff: boolean;
};

const CompareExperimentsConfigCell: React.FC<
  CellContext<CompareConfig, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onlyDiff } = (custom ?? {}) as CustomMeta;
  const experimentId = context.column?.id;
  const compareConfig = context.row.original;

  const data = compareConfig.data[experimentId];
  const baseData = compareConfig.data[compareConfig.base];

  const renderContent = () => {
    if (isUndefined(data)) {
      return <span className="px-1.5 py-2.5 text-light-slate">No value</span>;
    }

    return (
      <div className="comet-code size-full max-w-full overflow-hidden whitespace-pre-wrap break-words rounded-md border bg-[#FBFCFD] px-2 py-[11px]">
        {showDiffView ? (
          <TextDiff content1={toString(baseData)} content2={toString(data)} />
        ) : (
          toString(data)
        )}
      </div>
    );
  };

  const showDiffView =
    onlyDiff &&
    Object.values(compareConfig.data).length >= 2 &&
    experimentId !== compareConfig.base;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={{
        ...context.table.options.meta,
        rowHeight: ROW_HEIGHT.small,
        rowHeightStyle: { minHeight: "52px" },
      }}
      className="p-1.5"
    >
      {renderContent()}
    </CellWrapper>
  );
};

export default CompareExperimentsConfigCell;
