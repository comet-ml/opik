import React, { useCallback, useRef, useState } from "react";
import { Split, Trash, Tag } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { isTestSuiteExperiment } from "@/lib/experiments";
import { Button, ButtonProps } from "@/ui/button";
import { Experiment } from "@/types/datasets";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import CompareExperimentsDialog from "@/v2/pages-shared/experiments/CompareExperimentsDialog/CompareExperimentsDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { Separator } from "@/ui/separator";
import AddTagDialog from "@/v2/pages-shared/experiments/AddTagDialog/AddTagDialog";
import { usePermissions } from "@/contexts/PermissionsContext";

// Names the source type when the selection shares one; a selection mixing
// datasets and test suites falls back to "item source". Legacy experiments
// (no evaluation_method) count as datasets, like the Datasets tab.
const getMixedItemSourcesTooltip = (experiments: Experiment[]) => {
  const testSuiteCount = experiments.filter(isTestSuiteExperiment).length;
  const label =
    testSuiteCount === experiments.length
      ? "test suite"
      : testSuiteCount === 0
        ? "dataset"
        : "item source";
  return `Select experiments from the same ${label} to compare them`;
};

type ExperimentsActionsPanelsProps = {
  experiments?: Experiment[];
  onTagsAdded?: () => void;
  buttonVariant?: "outline" | "ghostInverted";
  buttonSize?: ButtonProps["size"];
};

const ExperimentsActionsPanel: React.FunctionComponent<
  ExperimentsActionsPanelsProps
> = ({
  experiments = [],
  onTagsAdded,
  buttonVariant = "outline",
  buttonSize = "sm",
}) => {
  const isInverted = buttonVariant === "ghostInverted";
  const iconButtonSize = buttonSize === "2xs" ? "icon-2xs" : "icon-sm";
  const leadIconClassName =
    buttonSize === "2xs" ? "mr-1 size-3" : "mr-1.5 size-3.5";
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const disabled = !experiments.length;
  const hasMixedItemSources = experiments.some(
    (e) => e.dataset_id !== experiments[0].dataset_id,
  );
  const compareDisabled = disabled || hasMixedItemSources;
  const {
    permissions: { canCreateExperiments },
  } = usePermissions();

  const navigateToCompare = (datasetId: string, experimentsIds: string[]) => {
    navigate({
      to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
      params: {
        datasetId,
        workspaceName,
        projectId: activeProjectId!,
      },
      search: {
        experiments: experimentsIds,
      },
    });
  };

  const handleCompareClick = () => {
    if (compareDisabled) return;

    if (experiments.length === 1) {
      setOpen(4);
      resetKeyRef.current = resetKeyRef.current + 1;
      return;
    }

    navigateToCompare(
      experiments[0].dataset_id,
      experiments.map((e) => e.id),
    );
  };

  const compareButton = (
    <Button
      variant={isInverted ? buttonVariant : "default"}
      size={buttonSize}
      onClick={handleCompareClick}
      disabled={compareDisabled}
    >
      <Split className={leadIconClassName} />
      Compare
    </Button>
  );

  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();

  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: experiments.map((e) => e.id),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiments]);

  return (
    <div className={cn("flex items-center", isInverted ? "gap-1" : "gap-2")}>
      {experiments.length === 1 && (
        <CompareExperimentsDialog
          key={`compare-${resetKeyRef.current}`}
          open={open === 4}
          setOpen={setOpen}
          datasetId={experiments[0].dataset_id}
          experimentsIds={[experiments[0].id]}
          onCompare={(ids) => navigateToCompare(experiments[0].dataset_id, ids)}
        />
      )}
      {canCreateExperiments && (
        <>
          <ConfirmDialog
            key={`delete-${resetKeyRef.current}`}
            open={open === 2}
            setOpen={setOpen}
            onConfirm={deleteExperimentsHandler}
            title="Delete experiments"
            description="Deleting experiments will remove all samples in these experiments. Related traces won't be affected. This action cannot be undone. Are you sure you want to continue?"
            confirmText="Delete experiments"
            confirmButtonVariant="destructive"
          />
          <AddTagDialog
            key={`tag-${resetKeyRef.current}`}
            experiments={experiments}
            open={open === 3}
            setOpen={setOpen}
            onSuccess={onTagsAdded}
          />
        </>
      )}
      <div className="inline-flex items-center gap-2">
        {hasMixedItemSources ? (
          <TooltipWrapper content={getMixedItemSourcesTooltip(experiments)}>
            <span className="inline-block cursor-not-allowed">
              {compareButton}
            </span>
          </TooltipWrapper>
        ) : (
          <TooltipWrapper content="Compare experiments">
            {compareButton}
          </TooltipWrapper>
        )}
        {!isInverted && (
          <ExplainerIcon
            className="-ml-0.5"
            {...EXPLAINERS_MAP[
              EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
            ]}
          />
        )}
        {canCreateExperiments && !isInverted && (
          <Separator orientation="vertical" className="mx-2 h-4" />
        )}
      </div>
      {canCreateExperiments && (
        <>
          <TooltipWrapper content="Manage tags">
            <Button
              variant={buttonVariant}
              size={isInverted ? buttonSize : iconButtonSize}
              aria-label="Manage tags"
              onClick={() => {
                setOpen(3);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <Tag className={isInverted ? leadIconClassName : undefined} />
              {isInverted && <span>Manage tags</span>}
            </Button>
          </TooltipWrapper>
          {isInverted && (
            <Separator
              orientation="vertical"
              className="mx-1 h-4 bg-white opacity-50"
            />
          )}
          <TooltipWrapper content="Delete">
            <Button
              variant={buttonVariant}
              size={iconButtonSize}
              aria-label="Delete"
              onClick={() => {
                setOpen(2);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <Trash />
            </Button>
          </TooltipWrapper>
        </>
      )}
    </div>
  );
};

export default ExperimentsActionsPanel;
