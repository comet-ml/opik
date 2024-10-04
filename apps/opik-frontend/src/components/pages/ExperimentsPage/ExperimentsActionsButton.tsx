import React, { useCallback, useRef, useState } from "react";
import { Split, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Experiment } from "@/types/datasets";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import FilterExperimentsToCompareDialog from "@/components/pages/ExperimentsPage/FilterExperimentsToCompareDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

type ExperimentsActionsButtonProps = {
  experiments: Experiment[];
};

const ExperimentsActionsButton: React.FunctionComponent<
  ExperimentsActionsButtonProps
> = ({ experiments }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
    <>
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
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="default">
            {`Actions (${experiments.length} selected)`}{" "}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-52">
          <DropdownMenuItem onClick={handleCompareClick}>
            <Split className="mr-2 size-4" />
            Compare
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
};

export default ExperimentsActionsButton;
