import React, { useCallback, useMemo } from "react";
import { Copy, MoreHorizontal, Share, Trash } from "lucide-react";
import copy from "clipboard-copy";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { ExecutionPolicy, DEFAULT_EXECUTION_POLICY } from "@/types/evaluation-suites";
import {
  DatasetItemEditorAutosaveProvider,
  useDatasetItemEditorAutosaveContext,
} from "@/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditorAutosaveContext";
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
import ItemBehaviorsSection from "./ItemBehaviorsSection";
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

const truncateId = (id: string): string => {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
};

const EvaluationSuiteItemPanelLayout: React.FC<{
  datasetItemId: string;
  datasetId: string;
  isOpen: boolean;
  onClose: () => void;
  suitePolicy: ExecutionPolicy;
}> = ({ datasetItemId, datasetId, isOpen, onClose, suitePolicy }) => {
  const {
    isPending,
    handleDelete,
    horizontalNavigation,
    flushPendingSave,
    resetSaveState,
  } = useDatasetItemEditorAutosaveContext();

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
    flushPendingSave();
    resetSaveState();
    onClose();
  }, [flushPendingSave, resetSaveState, onClose]);

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
            <TooltipWrapper content={datasetItemId}>
              <div className="comet-body-accented">
                Evaluation suite item{" "}
                <span className="comet-body-s text-muted-slate">
                  {truncateId(datasetItemId)}
                </span>
              </div>
            </TooltipWrapper>
          </div>

          <div className="flex flex-col gap-6 p-6 pt-4">
            <ItemDescriptionSection itemId={datasetItemId} />
            <ItemExecutionPolicySection
              itemId={datasetItemId}
              suitePolicy={suitePolicy}
            />
            <ItemBehaviorsSection
              itemId={datasetItemId}
              datasetId={datasetId}
            />
            <ItemContextSection />
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
        datasetId={datasetId}
        isOpen={isOpen}
        onClose={onClose}
        suitePolicy={suitePolicy}
      />
    </DatasetItemEditorAutosaveProvider>
  );
};

export default EvaluationSuiteItemPanel;
