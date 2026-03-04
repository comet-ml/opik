import React, { useCallback, useMemo } from "react";
import { Copy, MoreHorizontal, Share, Trash } from "lucide-react";
import copy from "clipboard-copy";
import { DatasetItem, DatasetItemColumn, Evaluator } from "@/types/datasets";
import {
  DEFAULT_EXECUTION_POLICY,
  ExecutionPolicy,
} from "@/types/evaluation-suites";
import {
  DatasetItemEditorAutosaveProvider,
  useDatasetItemEditorAutosaveContext,
} from "@/components/pages-shared/datasets/DatasetItemEditor/DatasetItemEditorAutosaveContext";
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
import ItemDescriptionSection from "./ItemDescriptionSection";
import ItemExecutionPolicySection from "./ItemExecutionPolicySection";
import ItemEvaluatorsSection from "./ItemEvaluatorsSection";
import ItemContextSection from "./ItemContextSection";

interface EvaluationSuiteItemPanelProps {
  datasetItemId: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  onClose: () => void;
  isOpen: boolean;
  rows: DatasetItem[];
  setActiveRowId: (id: string) => void;
  suitePolicy?: ExecutionPolicy;
}

function truncateId(id: string): string {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
}

interface EvaluationSuiteItemPanelLayoutProps {
  datasetItemId: string;
  isOpen: boolean;
  onClose: () => void;
  itemEvaluators?: Evaluator[];
  suitePolicy: ExecutionPolicy;
  savedItemPolicy?: ExecutionPolicy;
}

const EvaluationSuiteItemPanelLayout: React.FC<
  EvaluationSuiteItemPanelLayoutProps
> = ({
  datasetItemId,
  isOpen,
  onClose,
  itemEvaluators,
  suitePolicy,
  savedItemPolicy,
}) => {
  const { isPending, handleDelete, horizontalNavigation } =
    useDatasetItemEditorAutosaveContext();

  const { toast } = useToast();

  const handleShare = useCallback(() => {
    toast({ description: "URL successfully copied to clipboard" });
    copy(window.location.href);
  }, [toast]);

  const handleCopyId = useCallback(() => {
    toast({ description: "Item ID successfully copied to clipboard" });
    copy(datasetItemId);
  }, [datasetItemId, toast]);

  const handleDeleteItemConfirm = useCallback(() => {
    handleDelete(onClose);
  }, [handleDelete, onClose]);

  const handleClose = useCallback(() => {
    onClose();
  }, [onClose]);

  const headerContent = useMemo(
    () => (
      <div className="flex flex-auto items-center justify-end pl-6">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon-sm">
              <span className="sr-only">Actions menu</span>
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            <DropdownMenuItem onClick={handleShare}>
              <Share className="mr-2 size-4" />
              Share item
            </DropdownMenuItem>
            <TooltipWrapper content={datasetItemId} side="left">
              <DropdownMenuItem onClick={handleCopyId}>
                <Copy className="mr-2 size-4" />
                Copy item ID
              </DropdownMenuItem>
            </TooltipWrapper>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleDeleteItemConfirm}>
              <Trash className="mr-2 size-4" />
              Delete item
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    ),
    [datasetItemId, handleShare, handleCopyId, handleDeleteItemConfirm],
  );

  return (
    <ResizableSidePanel
      panelId="evaluation-suite-item-panel"
      entity="item"
      open={isOpen}
      headerContent={headerContent}
      onClose={handleClose}
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
              Evaluation suite item{" "}
              <TooltipWrapper content={datasetItemId}>
                <span className="comet-body-s text-muted-slate">
                  {truncateId(datasetItemId)}
                </span>
              </TooltipWrapper>
            </div>
          </div>

          <div className="flex flex-col gap-6 p-6 pt-4">
            <ItemDescriptionSection itemId={datasetItemId} />
            <ItemContextSection />
            <ItemExecutionPolicySection
              itemId={datasetItemId}
              suitePolicy={suitePolicy}
              savedItemPolicy={savedItemPolicy}
            />
            <ItemEvaluatorsSection
              itemId={datasetItemId}
              itemEvaluators={itemEvaluators}
            />
          </div>
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
  suitePolicy = DEFAULT_EXECUTION_POLICY,
}) => {
  const activeRow = useMemo(
    () => rows.find((r) => r.id === datasetItemId),
    [rows, datasetItemId],
  );

  const itemEvaluators = activeRow?.evaluators;
  const itemExecutionPolicy = activeRow?.execution_policy;

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
        itemEvaluators={itemEvaluators}
        suitePolicy={suitePolicy}
        savedItemPolicy={itemExecutionPolicy}
      />
    </DatasetItemEditorAutosaveProvider>
  );
};

export default EvaluationSuiteItemPanel;
