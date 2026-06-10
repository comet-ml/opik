import React, { useCallback } from "react";
import copy from "clipboard-copy";
import {
  DatasetItemColumn,
  DatasetItemWithDraft,
  Evaluator,
  DATASET_ITEM_DRAFT_STATUS,
} from "@/types/datasets";
import { ExecutionPolicy } from "@/types/test-suites";
import {
  DatasetItemEditorAutosaveProvider,
  useDatasetItemEditorAutosaveContext,
} from "@/v2/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorAutosaveContext";
import { prepareFormDataForSave } from "@/v2/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemFormHelpers";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import ResizableSidePanelArrowNavigation from "@/shared/ResizableSidePanel/ResizableSidePanelArrowNavigation";
import DatasetItemActionsDropdown from "@/v2/pages-shared/datasets/DatasetItemActionsDropdown/DatasetItemActionsDropdown";
import { useToast } from "@/ui/use-toast";
import Loader from "@/shared/Loader/Loader";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import TestSuiteItemFormContainer from "./TestSuiteItemFormContainer";
import {
  useEditItem,
  useUpdateItemAssertions,
} from "@/store/TestSuiteDraftStore";
import { extractAssertions } from "@/lib/assertion-converters";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useEffectiveItemAssertions } from "@/hooks/useEffectiveItemAssertions";
import { useEffectiveItemExecutionPolicy } from "@/hooks/useEffectiveItemExecutionPolicy";
import { useDatasetEntityIdFromURL } from "@/v2/hooks/useDatasetEntityIdFromURL";
import {
  TestSuiteItemFormValues,
  toFormValues,
  fromFormValues,
} from "./testSuiteItemFormSchema";

interface TestSuiteItemPanelProps {
  datasetItemId: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  onClose: () => void;
  isOpen: boolean;
  rows: DatasetItemWithDraft[];
  setActiveRowId: (id: string) => void;
  onOpenSettings: () => void;
}

interface TestSuiteItemPanelLayoutProps {
  datasetItemId: string;
  isOpen: boolean;
  onClose: () => void;
  savedItemPolicy?: ExecutionPolicy;
  serverEvaluators: Evaluator[];
  onOpenSettings: () => void;
  isNewItem: boolean;
  columns: DatasetItemColumn[];
}

const TestSuiteItemPanelLayout: React.FC<TestSuiteItemPanelLayoutProps> = ({
  datasetItemId,
  isOpen,
  onClose,
  savedItemPolicy,
  serverEvaluators,
  onOpenSettings,
  isNewItem,
  columns,
}) => {
  const {
    isPending,
    handleDelete,
    horizontalNavigation,
    tags,
    handleAddTag,
    handleDeleteTag,
    datasetItem,
  } = useDatasetItemEditorAutosaveContext();

  const { toast } = useToast();
  const editItem = useEditItem();
  const updateItemAssertions = useUpdateItemAssertions();
  const suiteId = useDatasetEntityIdFromURL();

  const description = datasetItem?.description ?? "";
  const data = (datasetItem?.data as Record<string, unknown>) ?? {};

  const suitePolicy = useEffectiveExecutionPolicy(suiteId);
  const itemPolicy = useEffectiveItemExecutionPolicy(
    datasetItemId,
    savedItemPolicy,
  );
  const currentPolicy = itemPolicy ?? suitePolicy;

  const suiteAssertions = useEffectiveSuiteAssertions(suiteId);
  const itemAssertions = useEffectiveItemAssertions(
    datasetItemId,
    serverEvaluators,
  );

  const initialValues = toFormValues(
    description,
    data,
    itemAssertions,
    currentPolicy,
    itemPolicy === null,
  );

  const handleFormChange = useCallback(
    (values: TestSuiteItemFormValues, changedField?: string) => {
      const { description, data, assertions, policy } = fromFormValues(values);

      const isAssertionChange = changedField?.startsWith("assertions");
      const isItemChange = !changedField || !isAssertionChange;
      const isAssertionOrNoField = !changedField || isAssertionChange;

      if (isItemChange) {
        const patch: Record<string, unknown> = {
          description,
          execution_policy: policy,
        };

        if (data !== null) {
          patch.data = prepareFormDataForSave(data, columns);
        }

        editItem(datasetItemId, patch);
      }

      if (isAssertionOrNoField) {
        updateItemAssertions(
          datasetItemId,
          assertions,
          extractAssertions(serverEvaluators),
        );
      }
    },
    [datasetItemId, columns, editItem, updateItemAssertions, serverEvaluators],
  );

  const handleShare = useCallback(() => {
    toast({ description: "URL successfully copied to clipboard" });
    copy(window.location.href);
  }, [toast]);

  const handleCopyId = useCallback(() => {
    toast({ description: "Item ID successfully copied to clipboard" });
    copy(datasetItemId);
  }, [datasetItemId, toast]);

  const handleDeleteItemConfirm = useCallback(
    () => handleDelete(onClose),
    [handleDelete, onClose],
  );

  return (
    <ResizableSidePanel
      panelId="test-suite-item-panel"
      open={isOpen}
      header={
        <ResizableSidePanelTopBar
          variant="info"
          title={isNewItem ? "Add item" : "Edit item"}
          onClose={onClose}
        >
          {!isNewItem && (
            <DatasetItemActionsDropdown
              datasetItemId={datasetItemId}
              onShare={handleShare}
              onCopyId={handleCopyId}
              onDelete={handleDeleteItemConfirm}
            />
          )}
          <ResizableSidePanelArrowNavigation
            horizontalNavigation={horizontalNavigation}
          />
        </ResizableSidePanelTopBar>
      }
      onClose={onClose}
      horizontalNavigation={horizontalNavigation}
    >
      {isPending ? (
        <div className="flex size-full items-center justify-center">
          <Loader />
        </div>
      ) : (
        <div className="relative size-full overflow-y-auto">
          <div className="sticky top-0 z-10 border-b bg-background px-6 py-4">
            <TagListRenderer
              tags={tags}
              onAddTag={handleAddTag}
              onDeleteTag={handleDeleteTag}
              size="sm"
              align="start"
            />
          </div>

          <TestSuiteItemFormContainer
            key={datasetItemId}
            initialValues={initialValues}
            suiteAssertions={suiteAssertions}
            suitePolicy={suitePolicy}
            onOpenSettings={onOpenSettings}
            onFormChange={handleFormChange}
          />
        </div>
      )}
    </ResizableSidePanel>
  );
};

const TestSuiteItemPanel: React.FC<TestSuiteItemPanelProps> = ({
  datasetItemId,
  datasetId,
  columns,
  onClose,
  isOpen,
  rows,
  setActiveRowId,
  onOpenSettings,
}) => {
  const activeRow = rows.find((r) => r.id === datasetItemId);

  const isNewItem = activeRow?.draftStatus === DATASET_ITEM_DRAFT_STATUS.added;
  const itemExecutionPolicy = activeRow?.execution_policy;
  const serverEvaluators = activeRow?.evaluators ?? [];

  return (
    <DatasetItemEditorAutosaveProvider
      datasetItemId={datasetItemId}
      datasetId={datasetId}
      columns={columns}
      rows={rows}
      setActiveRowId={setActiveRowId}
    >
      <TestSuiteItemPanelLayout
        datasetItemId={datasetItemId}
        isOpen={isOpen}
        onClose={onClose}
        savedItemPolicy={itemExecutionPolicy}
        serverEvaluators={serverEvaluators}
        onOpenSettings={onOpenSettings}
        isNewItem={isNewItem}
        columns={columns}
      />
    </DatasetItemEditorAutosaveProvider>
  );
};

export default TestSuiteItemPanel;
