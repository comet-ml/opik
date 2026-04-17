import React, { useCallback, useEffect, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import DatasetItemsTab, {
  EditPanelRenderProps,
  AddPanelRenderProps,
  AddDialogRenderProps,
} from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";
import EditTestSuiteSettingsDialog from "@/v2/pages-shared/datasets/TestSuiteComponents/EditTestSuiteSettingsDialog";
import TestSuiteItemPanel from "@/v2/pages-shared/datasets/TestSuiteComponents/TestSuiteItemPanel/TestSuiteItemPanel";
import AddDatasetItemPanel from "@/v2/pages-shared/datasets/DatasetItemPanel/AddDatasetItemPanel";
import AddDatasetItemDialog from "@/v2/pages-shared/datasets/DatasetItemPanel/AddDatasetItemDialog";
import AddTestSuiteItemPanel from "@/v2/pages-shared/datasets/TestSuiteComponents/TestSuiteItemPanel/AddTestSuiteItemPanel";
import DatasetItemEditor from "@/v2/pages-shared/datasets/DatasetItemEditor/DatasetItemEditor";
import AddVersionDialog from "@/v2/pages-shared/datasets/VersionHistoryTab/AddVersionDialog";
import VersionHistoryTab from "@/v2/pages-shared/datasets/VersionHistoryTab/VersionHistoryTab";
import OverrideVersionDialog from "@/v2/pages-shared/datasets/OverrideVersionDialog";
import DatasetExpansionDialog from "@/v2/pages-shared/datasets/DatasetExpansionDialog";
import { ExpansionDialogRenderProps } from "@/v2/pages-shared/datasets/DatasetItemsActionsPanel";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import Loader from "@/shared/Loader/Loader";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { useTestSuiteSavePayload } from "@/hooks/useTestSuiteSavePayload";
import { useDatasetEntityIdFromURL } from "@/v2/hooks/useDatasetEntityIdFromURL";
import { useClearDraft, useHasDraft } from "@/store/TestSuiteDraftStore";
import {
  DatasetItemColumn,
  DATASET_STATUS,
  DATASET_TYPE,
} from "@/types/datasets";
import { DynamicColumn } from "@/types/shared";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { useActiveProjectId } from "@/store/AppStore";

import {
  SUITE_STORAGE_KEYS,
  DATASET_STORAGE_KEYS,
  SUITE_DEFAULT_SELECTED_COLUMNS,
  DATASET_DEFAULT_SELECTED_COLUMNS,
  buildSuiteColumns,
  buildDatasetColumns,
} from "./datasetItemsPageConfig";
import useDatasetItemsSave from "./useDatasetItemsSave";
import DatasetItemsPageHeader from "./DatasetItemsPageHeader";
import { useState } from "react";

const POLLING_INTERVAL_MS = 3000;

function DatasetItemsPage(): React.ReactElement {
  const datasetId = useDatasetEntityIdFromURL();
  const activeProjectId = useActiveProjectId();

  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [settingsDialogOpen, setSettingsDialogOpen] = useState(false);

  const hasDraft = useHasDraft();
  const clearDraft = useClearDraft();

  const {
    permissions: { canEditDatasets },
  } = usePermissions();

  const { mutate: updateDataset } = useDatasetUpdateMutation();

  const { data: dataset, isPending } = useDatasetById(
    { datasetId },
    {
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return status === DATASET_STATUS.processing
          ? POLLING_INTERVAL_MS
          : false;
      },
    },
  );

  const datasetType = dataset?.type;
  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;
  const entityName = isTestSuite ? "test suite" : "dataset";

  const { data: versionsData } = useDatasetVersionsList(
    { datasetId, page: 1, size: 1 },
    { enabled: isTestSuite },
  );
  const latestVersionData = versionsData?.content?.[0];
  const versionEvaluators = latestVersionData?.evaluators ?? [];
  const { buildPayload, buildInitialVersionPayload, hasNoVersion } =
    useTestSuiteSavePayload({
      suiteId: datasetId,
      suite: dataset,
      versionEvaluators,
    });

  const effectiveAssertions = useEffectiveSuiteAssertions(datasetId);

  useEffect(() => {
    return clearDraft;
  }, [datasetId, clearDraft]);

  const { DialogComponent } = useNavigationBlocker({
    condition: hasDraft,
    title: "Unsaved changes",
    description:
      "You have unsaved draft changes. Are you sure you want to leave?",
    confirmText: "Leave without saving",
    cancelText: "Stay",
  });

  const {
    addVersionDialogOpen,
    setAddVersionDialogOpen,
    discardDialogOpen,
    setDiscardDialogOpen,
    overrideDialogOpen,
    setOverrideDialogOpen,
    changesMutation,
    handleSaveChanges,
    handleOverrideConfirm,
    handleDiscardChanges,
  } = useDatasetItemsSave({
    datasetId,
    datasetName: dataset?.name,
    datasetType,
    buildPayload,
    buildInitialVersionPayload,
    hasNoVersion,
    clearDraft,
  });

  const handleAddTag = (newTag: string) => {
    updateDataset({
      dataset: {
        ...dataset,
        id: datasetId,
        tags: [...(dataset?.tags ?? []), newTag],
      },
    });
  };

  const handleDeleteTag = (tag: string) => {
    updateDataset({
      dataset: {
        ...dataset,
        id: datasetId,
        tags: (dataset?.tags ?? []).filter((t) => t !== tag),
      },
    });
  };

  const buildColumns = useCallback(
    (
      datasetColumns: DatasetItemColumn[],
      dynamicDatasetColumns: DynamicColumn[],
    ) =>
      isTestSuite
        ? buildSuiteColumns()
        : buildDatasetColumns(datasetColumns, dynamicDatasetColumns),
    [isTestSuite],
  );

  const renderEditPanel = useCallback(
    (props: EditPanelRenderProps) =>
      isTestSuite ? (
        <TestSuiteItemPanel
          {...props}
          onOpenSettings={() => setSettingsDialogOpen(true)}
        />
      ) : (
        <DatasetItemEditor {...props} />
      ),
    [isTestSuite],
  );

  const renderAddPanel = useCallback(
    (props: AddPanelRenderProps) =>
      isTestSuite ? (
        <AddTestSuiteItemPanel
          {...props}
          onOpenSettings={() => setSettingsDialogOpen(true)}
        />
      ) : (
        <AddDatasetItemPanel {...props} />
      ),
    [isTestSuite],
  );

  const renderAddDialog = useMemo(
    () =>
      isTestSuite
        ? undefined
        : ({ key, datasetId: id, open, setOpen }: AddDialogRenderProps) => (
            <AddDatasetItemDialog
              key={key}
              datasetId={id}
              open={open}
              setOpen={setOpen}
            />
          ),
    [isTestSuite],
  );

  const renderExpansionDialog = useCallback(
    ({ open, setOpen, onSamplesGenerated }: ExpansionDialogRenderProps) => (
      <DatasetExpansionDialog
        datasetId={datasetId}
        open={open}
        setOpen={setOpen}
        onSamplesGenerated={onSamplesGenerated}
        datasetType={datasetType}
        suiteAssertions={isTestSuite ? effectiveAssertions : undefined}
      />
    ),
    [datasetId, datasetType, isTestSuite, effectiveAssertions],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-4">
      <AddVersionDialog
        open={addVersionDialogOpen}
        setOpen={setAddVersionDialogOpen}
        onConfirm={handleSaveChanges}
        isSubmitting={changesMutation.isPending}
      />
      <ConfirmDialog
        open={discardDialogOpen}
        setOpen={setDiscardDialogOpen}
        onConfirm={handleDiscardChanges}
        title="Discard changes"
        description={`Discarding will remove all unsaved edits to this ${entityName}. This action can't be undone. Are you sure you want to continue?`}
        confirmText="Discard changes"
        confirmButtonVariant="destructive"
      />
      <OverrideVersionDialog
        open={overrideDialogOpen}
        setOpen={setOverrideDialogOpen}
        onConfirm={handleOverrideConfirm}
      />
      <EditTestSuiteSettingsDialog
        open={settingsDialogOpen}
        setOpen={setSettingsDialogOpen}
      />
      {DialogComponent}
      <DatasetItemsPageHeader
        dataset={dataset}
        datasetId={datasetId}
        isTestSuite={isTestSuite}
        entityName={entityName}
        activeProjectId={activeProjectId}
        hasDraft={hasDraft}
        canEditDatasets={canEditDatasets}
        effectiveAssertions={effectiveAssertions}
        onAddTag={handleAddTag}
        onDeleteTag={handleDeleteTag}
        onDiscardClick={() => setDiscardDialogOpen(true)}
        onSaveClick={() => setAddVersionDialogOpen(true)}
        onSettingsClick={() => setSettingsDialogOpen(true)}
      />
      <Tabs value={tab ?? "items"} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            Items
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
            storageKeys={
              isTestSuite ? SUITE_STORAGE_KEYS : DATASET_STORAGE_KEYS
            }
            defaultSelectedColumns={
              isTestSuite
                ? SUITE_DEFAULT_SELECTED_COLUMNS
                : DATASET_DEFAULT_SELECTED_COLUMNS
            }
            entityName={entityName}
            buildColumns={buildColumns}
            renderEditPanel={renderEditPanel}
            renderAddPanel={renderAddPanel}
            renderAddDialog={renderAddDialog}
            renderExpansionDialog={renderExpansionDialog}
          />
        </TabsContent>
        <TabsContent value="version-history">
          <VersionHistoryTab datasetId={datasetId} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

export default DatasetItemsPage;
