import { Check, Loader2 } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import React, { useEffect, useMemo } from "react";
import PlaygroundOutputTable from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputTable";
import PlaygroundExperimentOutputActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundExperimentOutputActions";
import PlaygroundPromptOutput from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundPromptOutput";
import StatusMessage from "@/components/shared/StatusMessage/StatusMessage";
import { JsonObject } from "@/types/shared";
import {
  usePromptIds,
  useSetDatasetVariables,
  useSetDatasetSampleData,
  useDatasetFilters,
  useDatasetPage,
  useSetDatasetPage,
  useDatasetSize,
  useSetDatasetSize,
} from "@/store/PlaygroundStore";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetLoadingStatus from "@/hooks/useDatasetLoadingStatus";
import {
  DatasetItem,
  DatasetItemColumn,
  DATASET_STATUS,
} from "@/types/datasets";
import { transformDataColumnFilters } from "@/lib/filters";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";

interface PlaygroundOutputsProps {
  datasetId: string | null;
  versionHash?: string;
  runSingle: (promptId: string) => void;
  stopSingle: (promptId: string) => void;
  headerMaxWidth?: string;
}

const EMPTY_ITEMS: DatasetItem[] = [];
const EMPTY_COLUMNS: DatasetItemColumn[] = [];
const POLLING_INTERVAL_MS = 3000;

const PlaygroundOutputs = ({
  datasetId,
  versionHash,
  runSingle,
  stopSingle,
  headerMaxWidth,
}: PlaygroundOutputsProps) => {
  const promptIds = usePromptIds();
  const setDatasetVariables = useSetDatasetVariables();
  const setDatasetSampleData = useSetDatasetSampleData();
  const filters = useDatasetFilters();
  const page = useDatasetPage();
  const setPage = useSetDatasetPage();
  const size = useDatasetSize();
  const setSize = useSetDatasetSize();

  // Parse datasetId to extract plain ID for API calls (handles both "id" and "id::versionId" formats)
  const parsed = useMemo(() => parseDatasetVersionKey(datasetId), [datasetId]);
  const parsedDatasetId = parsed?.datasetId || datasetId;

  const { data: dataset } = useDatasetById(
    { datasetId: parsedDatasetId! },
    {
      enabled: !!parsedDatasetId,
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return status === DATASET_STATUS.processing
          ? POLLING_INTERVAL_MS
          : false;
      },
    },
  );

  const { isProcessing, showSuccessMessage } = useDatasetLoadingStatus({
    datasetStatus: dataset?.status,
  });

  // Transform data column filters before passing to API
  const transformedFilters = useMemo(
    () => (filters ? transformDataColumnFilters(filters) : filters),
    [filters],
  );

  const {
    data: datasetItemsData,
    isLoading: isLoadingDatasetItems,
    isPlaceholderData: isPlaceholderDatasetItems,
    isFetching: isFetchingDatasetItems,
  } = useDatasetItemsList(
    {
      datasetId: parsedDatasetId!,
      page,
      size,
      truncate: true,
      filters: transformedFilters,
      versionId: versionHash, // Send hash, not ID
    },
    {
      enabled: !!parsedDatasetId,
      refetchInterval: isProcessing ? POLLING_INTERVAL_MS : false,
      placeholderData: parsedDatasetId ? keepPreviousData : undefined,
    },
  );

  const datasetItems = datasetItemsData?.content || EMPTY_ITEMS;
  const datasetColumns = datasetItemsData?.columns || EMPTY_COLUMNS;
  const total = datasetItemsData?.total || 0;

  const isExperimentMode = !!parsedDatasetId;

  useEffect(() => {
    setDatasetVariables(datasetColumns.map((c) => c.name));
    setDatasetSampleData((datasetItems[0]?.data as JsonObject) ?? null);
  }, [setDatasetVariables, setDatasetSampleData, datasetColumns, datasetItems]);

  return (
    <div className="flex min-w-full flex-1 flex-col">
      {isExperimentMode ? (
        <>
          <PlaygroundExperimentOutputActions
            datasetId={datasetId}
            page={page}
            onChangePage={setPage}
            size={size}
            onChangeSize={setSize}
            total={total}
            isLoadingTotal={isProcessing}
            maxWidth={headerMaxWidth}
          />
          <div className="flex w-full flex-col pb-4 pt-0">
            {isProcessing && (
              <StatusMessage
                icon={Loader2}
                iconClassName="animate-spin"
                title="Dataset still loading"
                description="Experiments will run, but may not use the full dataset until loading completes."
                className="mb-2"
              />
            )}
            {showSuccessMessage && (
              <StatusMessage
                icon={Check}
                title="Dataset fully loaded"
                description="All items are now available."
                className="mb-2"
              />
            )}
            <PlaygroundOutputTable
              promptIds={promptIds}
              datasetItems={datasetItems}
              datasetColumns={datasetColumns}
              isLoadingDatasetItems={isLoadingDatasetItems}
              isFetchingData={
                isFetchingDatasetItems && isPlaceholderDatasetItems
              }
            />
          </div>
        </>
      ) : (
        <div className="flex w-full flex-1 border-t">
          {promptIds?.map((promptId, idx) => (
            <PlaygroundPromptOutput
              key={`output-${promptId}`}
              promptId={promptId}
              promptIndex={idx}
              onRun={() => runSingle(promptId)}
              onStop={() => stopSingle(promptId)}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default PlaygroundOutputs;
