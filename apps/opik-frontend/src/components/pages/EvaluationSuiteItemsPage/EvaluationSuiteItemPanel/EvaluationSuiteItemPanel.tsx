import React, { useCallback } from "react";
import { Copy, MoreHorizontal, Share, Trash } from "lucide-react";
import copy from "clipboard-copy";
import {
  DatasetItemColumn,
  DatasetItemWithDraft,
  Evaluator,
  DATASET_ITEM_DRAFT_STATUS,
} from "@/types/datasets";
import { ExecutionPolicy } from "@/types/evaluation-suites";
import {
  DatasetItemEditorAutosaveProvider,
  useDatasetItemEditorAutosaveContext,
} from "@/components/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorAutosaveContext";
import { prepareFormDataForSave } from "@/components/pages-shared/datasets/DatasetItemEditor/hooks/useDatasetItemFormHelpers";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import EvaluationSuiteItemFormContainer from "./EvaluationSuiteItemFormContainer";
import {
  useEditItem,
  useUpdateItemAssertions,
} from "@/store/EvaluationSuiteDraftStore";
import { extractAssertions } from "@/lib/assertion-converters";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useEffectiveItemAssertions } from "@/hooks/useEffectiveItemAssertions";
import { useEffectiveItemExecutionPolicy } from "@/hooks/useEffectiveItemExecutionPolicy";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import {
  EvaluationSuiteItemFormValues,
  toFormValues,
  fromFormValues,
} from "./evaluationSuiteItemFormSchema";

interface EvaluationSuiteItemPanelProps {
  datasetItemId: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  onClose: () => void;
  isOpen: boolean;
  rows: DatasetItemWithDraft[];
  setActiveRowId: (id: string) => void;
  onOpenSettings: () => void;
}

function truncateId(id: string): string {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
}

interface EvaluationSuiteItemPanelLayoutProps {
  datasetItemId: string;
  isOpen: boolean;
  onClose: () => void;
  savedItemPolicy?: ExecutionPolicy;
  serverEvaluators: Evaluator[];
  onOpenSettings: () => void;
  isNewItem: boolean;
  columns: DatasetItemColumn[];
}

const EvaluationSuiteItemPanelLayout: React.FC<
  EvaluationSuiteItemPanelLayoutProps
> = ({
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
  const suiteId = useSuiteIdFromURL();

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
  );

  const handleFormChange = useCallback(
    (values: EvaluationSuiteItemFormValues, changedField?: string) => {
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

  const headerContent = isNewItem ? null : (
    <div className="flex flex-auto items-center justify-end pl-6">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-sm">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              toast({ description: "URL successfully copied to clipboard" });
              copy(window.location.href);
            }}
          >
            <Share className="mr-2 size-4" />
            Share item
          </DropdownMenuItem>
          <TooltipWrapper content={datasetItemId} side="left">
            <DropdownMenuItem
              onClick={() => {
                toast({
                  description: "Item ID successfully copied to clipboard",
                });
                copy(datasetItemId);
              }}
            >
              <Copy className="mr-2 size-4" />
              Copy item ID
            </DropdownMenuItem>
          </TooltipWrapper>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => handleDelete(onClose)}>
            <Trash className="mr-2 size-4" />
            Delete item
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );

  return (
    <ResizableSidePanel
      panelId="evaluation-suite-item-panel"
      entity="item"
      open={isOpen}
      headerContent={headerContent}
      onClose={onClose}
      horizontalNavigation={horizontalNavigation}
    >
      {isPending ? (
        <div className="flex size-full items-center justify-center">
          <Loader />
        </div>
      ) : (
        <div className="relative size-full overflow-y-auto">
          <div className="sticky top-0 z-10 border-b bg-background p-6 pb-4">
            <div className="comet-body-accented">
              {isNewItem ? (
                "Add suite item"
              ) : (
                <>
                  Suite item{" "}
                  <TooltipWrapper content={datasetItemId}>
                    <span className="comet-body-s text-muted-slate">
                      {truncateId(datasetItemId)}
                    </span>
                  </TooltipWrapper>
                </>
              )}
            </div>
            <TagListRenderer
              tags={tags}
              onAddTag={handleAddTag}
              onDeleteTag={handleDeleteTag}
              size="sm"
              align="start"
            />
          </div>

          <EvaluationSuiteItemFormContainer
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

const EvaluationSuiteItemPanel: React.FC<EvaluationSuiteItemPanelProps> = ({
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
      <EvaluationSuiteItemPanelLayout
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

export default EvaluationSuiteItemPanel;
