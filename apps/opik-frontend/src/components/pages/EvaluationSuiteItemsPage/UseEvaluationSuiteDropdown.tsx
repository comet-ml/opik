import { useCallback, useRef, useState } from "react";
import { Blocks, ChevronDown, Code2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import AddExperimentDialog from "@/components/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useLoadPlayground from "@/hooks/useLoadPlayground";

export interface UseEvaluationSuiteDropdownProps {
  datasetName?: string;
  datasetId?: string;
  datasetVersionId?: string;
  disabled?: boolean;
}

function UseEvaluationSuiteDropdown({
  datasetName = "",
  datasetId = "",
  datasetVersionId,
  disabled = false,
}: UseEvaluationSuiteDropdownProps) {
  const resetKeyRef = useRef(0);
  const resetDialogKeyRef = useRef(0);
  const [openExperimentDialog, setOpenExperimentDialog] = useState(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState(false);

  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const handleLoadPlayground = useCallback(() => {
    loadPlayground({
      datasetId,
      datasetVersionId,
    });
  }, [loadPlayground, datasetId, datasetVersionId]);

  const handleOpenPlaygroundClick = () => {
    if (isPlaygroundEmpty) {
      handleLoadPlayground();
    } else {
      resetKeyRef.current += 1;
      setOpenConfirmDialog(true);
    }
  };

  return (
    <>
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openExperimentDialog}
        setOpen={setOpenExperimentDialog}
        datasetName={datasetName}
      />
      <ConfirmDialog
        key={resetKeyRef.current}
        open={openConfirmDialog}
        setOpen={setOpenConfirmDialog}
        onConfirm={handleLoadPlayground}
        title="Load evaluation suite into playground"
        description="Loading this evaluation suite into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Load evaluation suite"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" disabled={disabled}>
            Use evaluation suite
            <ChevronDown className="ml-2 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-80">
          <DropdownMenuItem
            onClick={handleOpenPlaygroundClick}
            disabled={disabled || isPendingProviderKeys}
          >
            <Blocks className="mr-2 mt-0.5 size-4 shrink-0 self-start" />
            <div className="comet-body-s flex flex-col">
              <span>Open in Playground</span>
              <span className="text-light-slate">
                Test prompts over your evaluation suite and run evaluations
                interactively
              </span>
            </div>
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              resetDialogKeyRef.current += 1;
              setOpenExperimentDialog(true);
            }}
            disabled={disabled}
          >
            <Code2 className="mr-2 mt-0.5 size-4 shrink-0 self-start" />
            <div className="comet-body-s flex flex-col">
              <span>Run an experiment</span>
              <span className="text-light-slate">
                Use this evaluation suite to run an experiment using the Python
                SDK
              </span>
            </div>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

export default UseEvaluationSuiteDropdown;
