import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { CompareConfig } from "@/components/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import { ROW_HEIGHT } from "@/types/shared";
import TextDiff, { DIFF_MODE } from "@/components/shared/CodeDiff/TextDiff";
import { toString } from "@/lib/utils";

type CustomMeta = {
  diffMode: DIFF_MODE;
};

const CompareConfigCell: React.FunctionComponent<
  CellContext<CompareConfig, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { diffMode } = (custom ?? {}) as CustomMeta;
  const experimentId = context.column?.id;
  const compareConfig = context.row.original;

  const data = compareConfig.data[experimentId];
  const baseData = compareConfig.data[compareConfig.base];

  const showDiffView =
    diffMode !== DIFF_MODE.none &&
    Object.values(compareConfig.data).length >= 2 &&
    experimentId !== compareConfig.base;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={{
        ...context.table.options.meta,
        rowHeight: ROW_HEIGHT.small,
        rowHeightClass: "min-h-14",
      }}
      className="px-3"
    >
      {showDiffView ? (
        <div className="max-w-full overflow-hidden whitespace-pre-line break-words">
          <TextDiff
            content1={toString(baseData)}
            content2={toString(data)}
            mode={diffMode}
          />
        </div>
      ) : (
        <div className="max-w-full overflow-hidden whitespace-pre-line break-words">
          {toString(data)}
        </div>
      )}
    </CellWrapper>
  );
};

export default CompareConfigCell;
