import React, { useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { X, Check, GitCommitVertical } from "lucide-react";

import Loader from "@/components/shared/Loader/Loader";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import DateTag from "@/components/shared/DateTag/DateTag";
import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useRestoreDatasetVersionMutation from "@/api/datasets/useRestoreDatasetVersionMutation";
import DatasetTagsList from "@/components/pages/DatasetItemsPage/DatasetTagsList";
import UseDatasetDropdown from "@/components/pages/DatasetItemsPage/UseDatasetDropdown";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import DatasetItemsTab from "@/components/pages/DatasetItemsPage/DatasetItemsTab/DatasetItemsTab";
import VersionHistoryTab from "@/components/pages/DatasetItemsPage/VersionHistoryTab/VersionHistoryTab";
import AddVersionDialog from "@/components/pages/DatasetItemsPage/VersionHistoryTab/AddVersionDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import { DATASET_STATUS } from "@/types/datasets";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";

const POLLING_INTERVAL_MS = 3000;

const DatasetItemsPageVersioned = () => {
  const datasetId = useDatasetIdFromURL();
  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);

  const restoreVersionMutation = useRestoreDatasetVersionMutation();

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

  const { data: itemsData } = useDatasetItemsList({
    datasetId,
    page: 1,
    size: 1,
  });

  const hasDraft = itemsData?.has_draft ?? false;
  const latestVersion = dataset?.latest_version;

  const handleDiscardChanges = () => {
    restoreVersionMutation.mutate({
      datasetId,
      versionRef: "latest",
      successMessage: {
        title: "Changes discarded",
        description: "The dataset has been restored to its last saved version.",
      },
    });
  };

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <AddVersionDialog
        open={addVersionDialogOpen}
        setOpen={setAddVersionDialogOpen}
        datasetId={datasetId}
        datasetName={dataset?.name}
      />
      <ConfirmDialog
        open={discardDialogOpen}
        setOpen={setDiscardDialogOpen}
        onConfirm={handleDiscardChanges}
        title="Discard changes"
        description="This will discard all uncommitted changes and restore the dataset to its last saved version. This action cannot be undone."
        confirmText="Discard changes"
        confirmButtonVariant="destructive"
      />
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {hasDraft && (
              <Tag variant="orange" size="md">
                Draft
              </Tag>
            )}
            <h1 className="comet-title-l truncate break-words leading-none">
              {dataset?.name}
            </h1>
          </div>
          <div className="flex items-center gap-2">
            {hasDraft && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setDiscardDialogOpen(true)}
                >
                  <X className="mr-1 size-4" />
                  Discard changes
                </Button>
                <Button
                  variant="default"
                  size="sm"
                  onClick={() => setAddVersionDialogOpen(true)}
                >
                  <Check className="mr-1 size-4" />
                  Save changes
                </Button>
                <Separator orientation="vertical" className="mx-2 h-6" />
              </>
            )}
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
        <div className="flex gap-2 overflow-x-auto">
          {dataset?.created_at && (
            <DateTag
              date={dataset?.created_at}
              resource={RESOURCE_TYPE.dataset}
            />
          )}
          {latestVersion && (
            <>
              <Tag
                size="md"
                variant="transparent"
                className="flex shrink-0 items-center gap-1"
              >
                <GitCommitVertical className="size-3 text-green-500" />
                {latestVersion.version_hash}
              </Tag>
              {latestVersion.tags?.map((tag) => (
                <ColoredTag
                  key={tag}
                  label={tag}
                  size="md"
                  IconComponent={GitCommitVertical}
                />
              ))}
            </>
          )}
          <Separator orientation="vertical" className="ml-1.5 mt-1 h-4" />
          <DatasetTagsList
            tags={dataset?.tags ?? []}
            dataset={dataset}
            datasetId={datasetId}
            className="w-auto"
          />
        </div>
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

export default DatasetItemsPageVersioned;
