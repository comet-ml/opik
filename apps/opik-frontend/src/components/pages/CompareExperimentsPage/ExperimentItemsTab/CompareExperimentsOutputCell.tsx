import React from "react";
import { CellContext } from "@tanstack/react-table";
import JsonView from "react18-json-view";
import get from "lodash/get";
import isObject from "lodash/isObject";

import { toString } from "@/lib/utils";
import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/VerticallySplitCellWrapper";

const CompareExperimentsOutputCell: React.FunctionComponent<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { outputKey = "" } = (custom ?? {}) as CustomMeta;
  const experimentCompare = context.row.original;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;

  const renderCodeContent = (data: object) => {
    return isSmall ? (
      <div className="comet-code flex size-full items-center truncate">
        {JSON.stringify(data, null, 2)}
      </div>
    ) : (
      <div className="size-full overflow-y-auto whitespace-normal">
        {data && (
          <JsonView
            src={data}
            theme="github"
            collapseStringsAfterLength={10000}
          />
        )}
      </div>
    );
  };

  const renderTextContent = (data: string) => {
    return isSmall ? (
      <span className="truncate">{data}</span>
    ) : (
      <div className="size-full overflow-y-auto">{data}</div>
    );
  };

  const renderContent = (item: ExperimentItem | undefined) => {
    const data = get(item, ["output", outputKey], undefined);

    if (!data) return "-";

    return isObject(data)
      ? renderCodeContent(data)
      : renderTextContent(toString(data));
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};
export default CompareExperimentsOutputCell;
