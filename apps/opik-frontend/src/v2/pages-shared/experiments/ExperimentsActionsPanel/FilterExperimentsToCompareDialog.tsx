import React, { useState } from "react";
import findIndex from "lodash/findIndex";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Button } from "@/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { Checkbox } from "@/ui/checkbox";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { formatExperimentSubtext } from "@/v2/pages-shared/experiments/formatExperimentSubtext";

type FilterExperimentsToCompareDialogProps = {
  experiments: Experiment[];
  open: boolean;
  setOpen: (open: boolean) => void;
};

const FilterExperimentsToCompareDialog: React.FunctionComponent<
  FilterExperimentsToCompareDialogProps
> = ({ experiments, open, setOpen }) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const [selectedExperiments, setSelectedExperiments] = useState(experiments);
  const isValid = selectedExperiments.every(
    (e) => e.dataset_id === selectedExperiments[0].dataset_id,
  );

  const compareHandler = () => {
    if (!isValid) return;

    navigate({
      to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
      params: {
        datasetId: selectedExperiments[0].dataset_id,
        workspaceName,
        projectId: activeProjectId!,
      },
      search: {
        experiments: selectedExperiments.map((e) => e.id),
      },
    });
  };

  const checkboxChangeHandler = (e: Experiment) => {
    setSelectedExperiments((state) => {
      const localExperiments = state.slice();
      const index = findIndex(localExperiments, (le) => le.id === e.id);

      if (index !== -1) {
        localExperiments.splice(index, 1);
      } else {
        localExperiments.push(e);
      }

      return localExperiments;
    });
  };

  const renderListItems = () => {
    return experiments.map((e) => {
      const checked =
        findIndex(selectedExperiments, (se) => se.id === e.id) !== -1;
      return (
        <label
          key={e.id}
          className="flex cursor-pointer flex-col gap-0.5 rounded p-1"
        >
          <div className="flex h-5 items-center gap-1.5">
            <Checkbox
              checked={checked}
              onCheckedChange={() => checkboxChangeHandler(e)}
              aria-label="Select experiment"
              className="mx-0.5"
            />
            <span className="comet-body-s truncate">{e.name}</span>
          </div>
          <div className="comet-body-xs break-words pl-[26px] text-light-slate">
            {formatExperimentSubtext(e, { withSourceName: true })}
          </div>
        </label>
      );
    });
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[640px]">
        <DialogHeader>
          <DialogTitle>Select experiments to compare</DialogTitle>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <ExplainerDescription description="You can only compare experiments that use the same item source. Please make sure all the experiments use the same item source." />
          <div className="mt-3 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
            {renderListItems()}
          </div>
        </div>
        <DialogFooter className="border-t pt-4">
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={compareHandler}>
              Compare experiments
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default FilterExperimentsToCompareDialog;
