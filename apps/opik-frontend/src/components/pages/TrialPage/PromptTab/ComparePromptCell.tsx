import React, { useMemo } from "react";
import isUndefined from "lodash/isUndefined";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import { PromptDisplay } from "@/components/pages-shared/prompts/PromptMessageDisplay";
import PromptDiff from "@/components/shared/CodeDiff/PromptDiff";

export type ComparePromptData = unknown;

export type ComparePromptConfig = {
  name: string;
  data: Record<string, ComparePromptData>;
  base: string;
  different: boolean;
};

type CustomMeta = {
  onlyDiff: boolean;
};

const ComparePromptCell: React.FC<CellContext<ComparePromptConfig, unknown>> = (
  context,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { onlyDiff } = (custom ?? {}) as CustomMeta;
  const experimentId = context.column?.id;
  const compareConfig = context.row.original;

  const data = compareConfig.data[experimentId];
  const baseData = compareConfig.data[compareConfig.base];

  const showDiffView = useMemo(
    () =>
      onlyDiff &&
      Object.values(compareConfig.data).length >= 2 &&
      experimentId !== compareConfig.base,
    [onlyDiff, compareConfig.data, experimentId, compareConfig.base],
  );

  const renderContent = () => {
    if (isUndefined(data) || data === "-") {
      return <span className="px-1.5 py-2.5 text-light-slate">No value</span>;
    }

    if (showDiffView) {
      return (
        <div className="size-full max-w-full overflow-hidden">
          <PromptDiff baseline={baseData} current={data} />
        </div>
      );
    }

    return (
      <div className="size-full max-w-full overflow-hidden">
        <PromptDisplay
          data={data}
          fallback={
            <span className="text-light-slate">Unable to parse prompt</span>
          }
        />
      </div>
    );
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={{
        ...context.table.options.meta,
        rowHeight: ROW_HEIGHT.small,
        rowHeightStyle: { minHeight: "120px" },
      }}
      className="p-1.5"
    >
      {renderContent()}
    </CellWrapper>
  );
};

export default ComparePromptCell;
