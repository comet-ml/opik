import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {
  useOutputLoadingByPromptDatasetItemId,
  useOutputStaleStatusByPromptDatasetItemId,
  useOutputValueByPromptDatasetItemId,
} from "@/store/PlaygroundStore";
import ReactMarkdown from "react-markdown";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";

interface PlaygroundOutputCellData {
  dataItemId: string;
}

interface CustomMeta {
  promptId: string;
}

const PlaygroundOutputCell: React.FunctionComponent<
  CellContext<PlaygroundOutputCellData, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { promptId } = (custom ?? {}) as CustomMeta;
  const originalRow = context.row.original;

  const value = useOutputValueByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const isLoading = useOutputLoadingByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const stale = useOutputStaleStatusByPromptDatasetItemId(
    promptId,
    originalRow.dataItemId,
  );

  const renderContent = () => {
    if (isLoading && !value) {
      return <PlaygroundOutputLoader />;
    }

    return (
      <ReactMarkdown className={stale ? "text-muted-gray" : ""}>
        {value}
      </ReactMarkdown>
    );
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="flex pt-5"
    >
      <div className="size-full">
        <div className="h-[var(--cell-top-height)]" />
        <div className="h-[calc(100%-var(--cell-top-height))] overflow-y-auto">
          {renderContent()}
        </div>
      </div>
    </CellWrapper>
  );
};

export default PlaygroundOutputCell;
