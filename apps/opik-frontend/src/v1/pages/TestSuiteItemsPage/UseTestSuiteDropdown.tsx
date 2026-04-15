import { useCallback, useRef, useState } from "react";
import { Blocks, ChevronDown, Code2 } from "lucide-react";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import AddExperimentDialog from "@/v1/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import { usePermissions } from "@/contexts/PermissionsContext";

export interface UseTestSuiteDropdownProps {
  datasetName?: string;
  datasetId?: string;
  datasetVersionId?: string;
  disabled?: boolean;
  isTestSuite?: boolean;
}

function UseTestSuiteDropdown({
  datasetName = "",
  datasetId = "",
  datasetVersionId,
  disabled = false,
  isTestSuite = true,
}: UseTestSuiteDropdownProps) {
  const resetKeyRef = useRef(0);
  const resetDialogKeyRef = useRef(0);
  const [openExperimentDialog, setOpenExperimentDialog] = useState(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState(false);

  const {
    permissions: { canViewExperiments, canCreateExperiments, canUsePlayground },
  } = usePermissions();

  const hasAnyAction = canUsePlayground || canCreateExperiments;

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

  if (!hasAnyAction) return null;

  return (
    <>
      {canViewExperiments && (
        <AddExperimentDialog
          key={`experiment-dialog-${resetDialogKeyRef.current}`}
          open={openExperimentDialog}
          setOpen={setOpenExperimentDialog}
          datasetName={datasetName}
        />
      )}
      {canUsePlayground && (
        <ConfirmDialog
          key={`confirm-dialog-${resetKeyRef.current}`}
          open={openConfirmDialog}
          setOpen={setOpenConfirmDialog}
          onConfirm={handleLoadPlayground}
          title={`Load ${
            isTestSuite ? "test suite" : "dataset"
          } into playground`}
          description={`Loading this ${
            isTestSuite ? "test suite" : "dataset"
          } into the Playground will replace any unsaved changes. This action cannot be undone.`}
          confirmText={`Load ${isTestSuite ? "test suite" : "dataset"}`}
        />
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" disabled={disabled}>
            {isTestSuite ? "Use suite" : "Use dataset"}
            <ChevronDown className="ml-2 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-80">
          {canUsePlayground && (
            <DropdownMenuItem
              onClick={handleOpenPlaygroundClick}
              disabled={disabled || isPendingProviderKeys}
            >
              <Blocks className="mr-2 mt-0.5 size-4 shrink-0 self-start" />
              <div className="comet-body-s flex flex-col">
                <span>Open in Playground</span>
                <span className="text-light-slate">
                  Test prompts over your{" "}
                  {isTestSuite ? "test suite" : "dataset"} and run evaluations
                  interactively
                </span>
              </div>
            </DropdownMenuItem>
          )}
          {canCreateExperiments && (
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
                  Use this {isTestSuite ? "test suite" : "dataset"} to run an
                  experiment using the Python SDK
                </span>
              </div>
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

export default UseTestSuiteDropdown;
