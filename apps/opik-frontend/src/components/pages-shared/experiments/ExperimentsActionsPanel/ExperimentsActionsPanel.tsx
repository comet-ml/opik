import React, { useCallback, useRef, useState, useMemo } from "react";
import { Split, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Experiment } from "@/types/datasets";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import FilterExperimentsToCompareDialog from "@/components/pages-shared/experiments/ExperimentsActionsPanel/FilterExperimentsToCompareDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { Separator } from "@/components/ui/separator";
import { ResponsiveToolbarProvider } from "@/contexts/ResponsiveToolbarContext";
import { ResponsiveButton } from "@/components/ui/ResponsiveButton";

type ExperimentsActionsPanelsProps = {
  experiments: Experiment[];
};

const ExperimentsActionsPanel: React.FunctionComponent<
  ExperimentsActionsPanelsProps
> = ({ experiments }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const disabled = !experiments?.length;

  const toolbarElements = useMemo(
    () => [
      { name: "COMPARE", size: 110, visible: true },
      { name: "EXPLAINER", size: 24, visible: true },
      { name: "SEPARATOR", size: 25, visible: true },
      { name: "GAP", size: 8, visible: true },
      { name: "DELETE", size: 40, visible: true },
    ],
    [],
  );

  const handleCompareClick = () => {
    if (experiments.length === 0) return;

    const hasTheSameDataset = experiments.every(
      (e) => e.dataset_id === experiments[0].dataset_id,
    );

    if (hasTheSameDataset) {
      navigate({
        to: "/$workspaceName/experiments/$datasetId/compare",
        params: {
          datasetId: experiments[0].dataset_id,
          workspaceName,
        },
        search: {
          experiments: experiments.map((e) => e.id),
        },
      });
    } else {
      setOpen(1);
      resetKeyRef.current = resetKeyRef.current + 1;
    }
  };

  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();

  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: experiments.map((e) => e.id),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiments]);

  return (
    <ResponsiveToolbarProvider elements={toolbarElements}>
      <div className="flex items-center gap-2">
        <FilterExperimentsToCompareDialog
          key={resetKeyRef.current}
          experiments={experiments}
          open={open === 1}
          setOpen={setOpen}
        />
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
        <div className="inline-flex items-center gap-2">
          <ResponsiveButton
            text="Compare"
            icon={<Split />}
            onClick={handleCompareClick}
            disabled={disabled}
          />
          <ExplainerIcon
            className="-ml-0.5"
            {...EXPLAINERS_MAP[
              EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
            ]}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
        </div>
        <TooltipWrapper content="Delete">
          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            disabled={disabled}
          >
            <Trash />
          </Button>
        </TooltipWrapper>
      </div>
    </ResponsiveToolbarProvider>
  );
};

export default ExperimentsActionsPanel;
