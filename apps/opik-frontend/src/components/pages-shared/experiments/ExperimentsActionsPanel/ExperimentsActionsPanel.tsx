import React, { useCallback, useRef, useState } from "react";
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
        description="Are you sure you want to delete all selected experiments?"
        confirmText="Delete experiments"
      />
      <div className="inline-flex items-center gap-2">
        <TooltipWrapper content="Compare experiments">
          <Button
            variant="outline"
            size="sm"
            onClick={handleCompareClick}
            disabled={disabled}
          >
            <Split className="mr-2 size-3.5" />
            Compare
          </Button>
        </TooltipWrapper>
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
  );
};

export default ExperimentsActionsPanel;
