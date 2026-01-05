import React, { useState, useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { X, Check, GitCommitVertical } from "lucide-react";

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
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import { DATASET_STATUS } from "@/types/datasets";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import {
  useIsDraftMode,
  useClearDraft,
  useGetChangesPayload,
} from "@/store/DatasetDraftStore";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import OverrideVersionDialog from "@/components/pages/DatasetItemsPage/OverrideVersionDialog";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import { AxiosError } from "axios";

const POLLING_INTERVAL_MS = 3000;

const DatasetItemsPageVersioned = () => {
  const datasetId = useDatasetIdFromURL();
  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [pendingVersionData, setPendingVersionData] = useState<{
    tags?: string[];
    changeDescription?: string;
  } | null>(null);

  const hasDraft = useIsDraftMode();
  const clearDraft = useClearDraft();
  const getChangesPayload = useGetChangesPayload();

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

  const latestVersion = dataset?.latest_version;

  useEffect(() => {
    return () => {
      clearDraft();
    };
  }, [datasetId, clearDraft]);

  const { DialogComponent } = useNavigationBlocker({
    condition: hasDraft,
    title: "Unsaved changes",
    description:
      "You have unsaved draft changes. Are you sure you want to leave?",
    confirmText: "Leave without saving",
    cancelText: "Stay",
  });

  const changesMutation = useDatasetItemChangesMutation({
    onConflict: () => {
      setOverrideDialogOpen(true);
    },
  });

  const handleSaveChanges = (tags?: string[], changeDescription?: string) => {
    const changes = getChangesPayload();
    const baseVersion = dataset?.latest_version?.id || "";

    changesMutation.mutate(
      {
        datasetId,
        payload: {
          added_items: changes.addedItems,
          edited_items: changes.editedItems,
          deleted_ids: changes.deletedIds,
          base_version: baseVersion,
          tags,
          change_description: changeDescription,
        },
        override: false,
      },
      {
        onSuccess: () => {
          clearDraft();
          setAddVersionDialogOpen(false);
        },
        onError: (error) => {
          // If 409, store the version data for retry
          if ((error as AxiosError).response?.status === 409) {
            setPendingVersionData({ tags, changeDescription });
          }
        },
      },
    );
  };

  const handleOverrideConfirm = () => {
    if (!pendingVersionData) return;

    const changes = getChangesPayload();
    const baseVersion = dataset?.latest_version?.id || "";

    changesMutation.mutate(
      {
        datasetId,
        payload: {
          added_items: changes.addedItems,
          edited_items: changes.editedItems,
          deleted_ids: changes.deletedIds,
          base_version: baseVersion,
          tags: pendingVersionData.tags,
          change_description: pendingVersionData.changeDescription,
        },
        override: true,
      },
      {
        onSuccess: () => {
          clearDraft();
          setAddVersionDialogOpen(false);
          setOverrideDialogOpen(false);
          setPendingVersionData(null);
        },
      },
    );
  };

  const handleDiscardChanges = () => {
    clearDraft();
    setDiscardDialogOpen(false);
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
        onConfirm={handleSaveChanges}
      />
      <ConfirmDialog
        open={discardDialogOpen}
        setOpen={setDiscardDialogOpen}
        onConfirm={handleDiscardChanges}
        title="Discard changes"
        description="Discarding will remove all unsaved edits to this dataset. This action can't be undone. Are you sure you want to continue?"
        confirmText="Discard changes"
        confirmButtonVariant="destructive"
      />
      <OverrideVersionDialog
        open={overrideDialogOpen}
        setOpen={setOverrideDialogOpen}
        onConfirm={handleOverrideConfirm}
      />
      {DialogComponent}
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {hasDraft && (
              <Tag variant="orange" size="md">
                Draft
              </Tag>
            )}
            <h1 className="comet-title-l truncate break-words">
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
              datasetVersionId={latestVersion?.id}
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
                {latestVersion.version_name}
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
            className="min-h-0 w-auto"
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
