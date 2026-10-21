import React from "react";

import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import PlaygroundExperimentName from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundExperimentName";
import PlaygroundProgressIndicator from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundProgressIndicator";
import { useIsRunning } from "@/store/PlaygroundStore";

interface PlaygroundExperimentOutputActionsProps {
  datasetId: string | null;
  page: number;
  onChangePage: (page: number) => void;
  size: number;
  onChangeSize: (size: number) => void;
  total: number;
  isLoadingTotal?: boolean;
}

const PlaygroundExperimentOutputActions = ({
  datasetId,
  page,
  onChangePage,
  size,
  onChangeSize,
  total,
  isLoadingTotal,
}: PlaygroundExperimentOutputActionsProps) => {
  const isRunning = useIsRunning();

  const isExperimentMode = !!datasetId;

  if (!isExperimentMode) return null;

  return (
    <div className="border-y">
      {isRunning ? (
        <div className="px-4 pb-3 pt-2">
          <PlaygroundProgressIndicator />
        </div>
      ) : (
        <div className="flex items-center justify-between bg-gray-100 py-3 pl-2 pr-4">
          <PlaygroundExperimentName />
          <div className="shrink-0">
            <DataTablePagination
              page={page}
              pageChange={onChangePage}
              size={size}
              sizeChange={onChangeSize}
              total={total}
              variant="minimal"
              itemsPerPage={[10, 50, 100, 200, 500, 1000]}
              disabled={isRunning}
              isLoadingTotal={isLoadingTotal}
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default PlaygroundExperimentOutputActions;
