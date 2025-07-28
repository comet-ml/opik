import React, { useState } from "react";
import findIndex from "lodash/findIndex";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useAppStore from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Button } from "@/components/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { Checkbox } from "@/components/ui/checkbox";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";

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
  const [selectedExperiments, setSelectedExperiments] = useState(experiments);
  const isValid = selectedExperiments.every(
    (e) => e.dataset_id === selectedExperiments[0].dataset_id,
  );

  const compareHandler = () => {
    if (!isValid) return;

    navigate({
      to: "/$workspaceName/experiments/$datasetId/compare",
      params: {
        datasetId: selectedExperiments[0].dataset_id,
        workspaceName,
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
          className="flex cursor-pointer flex-col gap-0.5 py-2.5 pl-3 pr-4"
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <Checkbox
                checked={checked}
                onCheckedChange={() => checkboxChangeHandler(e)}
                aria-label="Select experiment"
                className="mt-0.5"
              />
              <span className="comet-body-s-accented truncate">{e.name}</span>
            </div>
            <div className="comet-body-s truncate pl-6 text-light-slate">
              Dataset: {e.dataset_name ?? "Deleted dataset"}
            </div>
          </div>
        </label>
      );
    });
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Select experiments to compare</DialogTitle>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <ExplainerDescription description="You can only compare experiments that use the same dataset. Please make sure all the experiments use the same dataset." />
          <div className="my-4 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch gap-2.5 overflow-y-auto">
            {renderListItems()}
          </div>
        </div>
        <DialogFooter>
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
