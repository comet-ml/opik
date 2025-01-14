import React from "react";
import PlaygroundOutputTable from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputTable";
import PlaygroundOutputActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/PlaygroundOutputActions";
import PlaygroundOutput from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";
import { usePromptIds } from "@/store/PlaygroundStore";
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
        <div className="flex w-full py-2">
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
      <div className="flex w-full gap-[var(--item-gap)] py-2">
        {promptIds?.map((promptId, promptIdx) => (
          <PlaygroundOutput
            key={`output-${promptId}`}
            promptId={promptId}
            index={promptIdx}
          />
        ))}
      </div>
    );
  };

  return (
    <div className="mt-auto flex min-w-full flex-col border-t">
      <PlaygroundOutputActions
        datasetId={datasetId}
        datasetItems={datasetItems}
        workspaceName={workspaceName}
        onChangeDatasetId={onChangeDatasetId}
        loadingDatasetItems={isLoadingDatasetItems}
      />
      {renderResult()}
    </div>
  );
};

export default PlaygroundOutputs;
