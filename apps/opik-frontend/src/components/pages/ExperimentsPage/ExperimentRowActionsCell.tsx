import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Experiment } from "@/types/datasets";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";

export const ExperimentRowActionsCell: React.FunctionComponent<
  CellContext<Experiment, unknown>
> = ({ row }) => {
  const resetKeyRef = useRef(0);
  const experiment = row.original;
  const [open, setOpen] = useState<boolean>(false);

  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();

  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: [experiment.id],
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiment]);

  return (
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteExperimentsHandler}
        title={`Delete ${experiment.name}`}
        description="Are you sure you want to delete this experiment?"
        confirmText="Delete experiment"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};
