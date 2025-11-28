import React, { useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import Loader from "@/components/shared/Loader/Loader";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import DateTag from "@/components/shared/DateTag/DateTag";
import useDatasetById from "@/api/datasets/useDatasetById";
import DatasetTagsList from "@/components/pages/DatasetItemsPage/DatasetTagsList";
import UseDatasetDropdown from "@/components/pages/DatasetItemsPage/UseDatasetDropdown";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import DatasetItemsTab from "@/components/pages/DatasetItemsPage/DatasetItemsTab/DatasetItemsTab";
import VersionHistoryTab from "@/components/pages/DatasetItemsPage/VersionHistoryTab/VersionHistoryTab";
import AddVersionDialog from "@/components/pages/DatasetItemsPage/VersionHistoryTab/AddVersionDialog";
import { Button } from "@/components/ui/button";
import { DATASET_STATUS } from "@/types/datasets";

const POLLING_INTERVAL_MS = 3000;

const DatasetItemsPage = () => {
  const datasetId = useDatasetIdFromURL();
  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);

  const { data: dataset, isPending } = useDatasetById(
    {
      datasetId,
    },
    {
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return status === DATASET_STATUS.processing
          ? POLLING_INTERVAL_MS
          : false;
      },
    },
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <AddVersionDialog
        open={addVersionDialogOpen}
        setOpen={setAddVersionDialogOpen}
        datasetId={datasetId}
      />
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <h1 className="comet-title-l truncate break-words">
            {dataset?.name}
          </h1>
          <div className="flex items-center gap-2">
            <Button
              variant="default"
              size="sm"
              onClick={() => setAddVersionDialogOpen(true)}
            >
              Save changes
            </Button>
            <UseDatasetDropdown
              datasetName={dataset?.name}
              datasetId={datasetId}
            />
          </div>
        </div>
        {dataset?.description && (
          <div className="-mt-3 mb-4 text-muted-slate">
            {dataset.description}
          </div>
        )}
        {dataset?.created_at && (
          <div className="mb-2 flex gap-2 overflow-x-auto">
            <DateTag
              date={dataset?.created_at}
              resource={RESOURCE_TYPE.dataset}
            />
          </div>
        )}
        <DatasetTagsList
          tags={dataset?.tags ?? []}
          dataset={dataset}
          datasetId={datasetId}
        />
      </div>
      <Tabs value={tab || "items"} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            Dataset items
          </TabsTrigger>
          <TabsTrigger variant="underline" value="version-history">
            Version history
          </TabsTrigger>
        </TabsList>
        <TabsContent value="items">
          <DatasetItemsTab
            datasetId={datasetId}
            datasetName={dataset?.name}
            datasetStatus={dataset?.status}
          />
        </TabsContent>
        <TabsContent value="version-history">
          <VersionHistoryTab datasetId={datasetId} />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default DatasetItemsPage;
