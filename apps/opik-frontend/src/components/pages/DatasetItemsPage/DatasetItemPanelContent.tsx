import React from "react";
import { keepPreviousData } from "@tanstack/react-query";
import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";

type DatasetItemPanelContentProps = {
  datasetItemId: string;
};

const DatasetItemPanelContent: React.FunctionComponent<
  DatasetItemPanelContentProps
> = ({ datasetItemId }) => {
  const { data, isPending } = useDatasetItemById(
    {
      datasetItemId,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  if (isPending) {
    return <Loader />;
  }

  if (!data) {
    return <NoData />;
  }

  return (
    <div className="relative size-full">
      <div className="size-full overflow-y-auto p-4">
        <h2 className="comet-title-m mb-4">Data</h2>
        <SyntaxHighlighter data={data.data} />
      </div>
    </div>
  );
};

export default DatasetItemPanelContent;
