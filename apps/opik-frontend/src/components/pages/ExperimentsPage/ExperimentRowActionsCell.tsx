import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pen, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { UpdateExperimentDialog } from "@/components/shared/UpdateExperimentDialog/UpdateExperimentDialog";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";

const ExperimentRowActionsCell: React.FunctionComponent<
  CellContext<GroupedExperiment, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const experiment = context.row.original;
  const [open, setOpen] = useState<boolean>(false);
  const [openEditPanel, setOpenEditPanel] = useState<boolean>(false);
  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();
  const experimentUpdateMutation = useExperimentUpdateMutation();
  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: [experiment.id],
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiment]);

  const updateExperimentHandler = useCallback((name: string, metadata: object) => {
    experimentUpdateMutation.mutate({
      id: experiment.id,
      name: name,
      metadata: metadata
    })
  }, [experiment])

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteExperimentsHandler}
        title="Delete experiment"
        description="Deleting an experiment will remove all samples in the experiment. Related traces won’t be affected. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete experiment"
        confirmButtonVariant="destructive"
      />

      <UpdateExperimentDialog
      open={openEditPanel}
      setOpen={setOpenEditPanel}
      onConfirm={updateExperimentHandler}
      latestName={experiment.name}
      latestMetadata={experiment.metadata}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpenEditPanel(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pen className="mr-2 size-4" />
            Update
          </DropdownMenuItem>
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
    </CellWrapper>
  );
};

export default ExperimentRowActionsCell;
