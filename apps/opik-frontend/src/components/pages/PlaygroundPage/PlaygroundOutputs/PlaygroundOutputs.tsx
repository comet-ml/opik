import React, { useEffect } from "react";
import PlaygroundOutputTable from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputTable";
import PlaygroundOutputActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/PlaygroundOutputActions";
import PlaygroundOutput from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";
import { usePromptIds, useSetDatasetVariables } from "@/store/PlaygroundStore";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";

interface PlaygroundOutputsProps {
  workspaceName: string;
  datasetId: string | null;
  onChangeDatasetId: (id: string | null) => void;
}

const EMPTY_ITEMS: DatasetItem[] = [];
const EMPTY_COLUMNS: DatasetItemColumn[] = [];

const PlaygroundOutputs = ({
  workspaceName,
  datasetId,
  onChangeDatasetId,
}: PlaygroundOutputsProps) => {
  const promptIds = usePromptIds();
  const setDatasetVariables = useSetDatasetVariables();

  const { data: datasetItemsData, isLoading: isLoadingDatasetItems } =
    useDatasetItemsList(
      {
        datasetId: datasetId!,
        page: 1,
        size: 1000,
        truncate: true,
      },
      {
        enabled: !!datasetId,
      },
    );

  const datasetItems = datasetItemsData?.content || EMPTY_ITEMS;
  const datasetColumns = datasetItemsData?.columns || EMPTY_COLUMNS;

  const renderResult = () => {
    if (datasetId) {
      return (
        <div className="flex w-full pb-4 pt-2">
          <PlaygroundOutputTable
            promptIds={promptIds}
            datasetItems={datasetItems}
            datasetColumns={datasetColumns}
            isLoadingDatasetItems={isLoadingDatasetItems}
          />
        </div>
      );
    }

    return (
      <div className="flex w-full gap-[var(--item-gap)] pb-4 pt-2">
        {promptIds?.map((promptId) => (
          <PlaygroundOutput
            key={`output-${promptId}`}
            promptId={promptId}
            totalOutputs={promptIds.length}
          />
        ))}
      </div>
    );
  };

  useEffect(() => {
    setDatasetVariables(datasetColumns.map((c) => c.name));
  }, [setDatasetVariables, datasetColumns]);

  return (
    <div className="flex min-w-full flex-col">
      <PlaygroundOutputActions
        datasetId={datasetId}
        datasetItems={datasetItems}
        datasetColumns={datasetColumns}
        workspaceName={workspaceName}
        onChangeDatasetId={onChangeDatasetId}
        loadingDatasetItems={isLoadingDatasetItems}
      />
      {renderResult()}
    </div>
  );
};

export default PlaygroundOutputs;
