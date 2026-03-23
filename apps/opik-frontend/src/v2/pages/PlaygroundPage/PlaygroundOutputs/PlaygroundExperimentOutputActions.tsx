import React from "react";
import { ExternalLink } from "lucide-react";

import { Button } from "@/ui/button";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import PlaygroundProgressIndicator from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundProgressIndicator";
import { useCreatedExperiments, useIsRunning } from "@/store/PlaygroundStore";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";

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
  const createdExperiments = useCreatedExperiments();
  const { navigate } = useNavigateToExperiment();

  const parsedDatasetId = parseDatasetVersionKey(datasetId);
  const plainDatasetId = parsedDatasetId?.datasetId || datasetId;

  const isExperimentMode = !!datasetId;
  const hasExperiments = createdExperiments.length > 0;

  const handleNavigateToExperiments = () => {
    if (createdExperiments.length > 0 && plainDatasetId) {
      navigate({
        experimentIds: createdExperiments.map((e) => e.id),
        datasetId: plainDatasetId,
      });
    }
  };

  if (!isExperimentMode) return null;

  return (
    <div className="border-y">
      {isRunning ? (
        <div className="px-4 pb-3 pt-2">
          <PlaygroundProgressIndicator />
        </div>
      ) : hasExperiments ? (
        <div className="flex items-center justify-between bg-gray-100 px-4 py-3">
          <Button
            variant="ghost"
            size="sm"
            className="text-sm text-muted-slate"
            onClick={handleNavigateToExperiments}
          >
            <span>Experiment results</span>
            <ExternalLink className="ml-1 size-3.5 shrink-0" />
          </Button>
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
      ) : null}
    </div>
  );
};

export default PlaygroundExperimentOutputActions;
