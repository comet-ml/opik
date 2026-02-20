import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pencil, Trash, Sparkles } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { UpdateExperimentDialog } from "@/components/shared/UpdateExperimentDialog/UpdateExperimentDialog";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";
import RunExperimentEvaluationDialog from "@/components/pages/ExperimentsPage/RunExperimentEvaluationDialog";

type ActiveDialog = "none" | "delete" | "edit" | "evaluate";

const ExperimentRowActionsCell: React.FunctionComponent<
  CellContext<GroupedExperiment, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const experiment = context.row.original;
  const [activeDialog, setActiveDialog] = useState<ActiveDialog>("none");

  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();
  const experimentUpdateMutation = useExperimentUpdateMutation();

  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: [experiment.id],
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiment]);

  const updateExperimentHandler = useCallback(
    (name: string, configuration: object) => {
      experimentUpdateMutation.mutate({
        experiment: {
          id: experiment.id,
          name: name,
          metadata: configuration,
        },
      });
    },
    [experiment, experimentUpdateMutation],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <RunExperimentEvaluationDialog
        key={`evaluate-${resetKeyRef.current}`}
        open={activeDialog === "evaluate"}
        setOpen={(open) => setActiveDialog(open ? "evaluate" : "none")}
        projectId={experiment.project_id || ""}
        experimentIds={[experiment.id]}
      />
      <UpdateExperimentDialog
        key={`edit-${resetKeyRef.current}`}
        open={activeDialog === "edit"}
        setOpen={(open) => setActiveDialog(open ? "edit" : "none")}
        onConfirm={updateExperimentHandler}
        latestName={experiment.name}
        latestConfiguration={experiment.metadata}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={activeDialog === "delete"}
        setOpen={(open) => setActiveDialog(open ? "delete" : "none")}
        onConfirm={deleteExperimentsHandler}
        title="Delete experiment"
        description="Deleting an experiment will remove all samples in the experiment. Related traces won't be affected. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete experiment"
        confirmButtonVariant="destructive"
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
              setActiveDialog("evaluate");
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Sparkles className="mr-2 size-4" />
            Evaluate
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setActiveDialog("edit");
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setActiveDialog("delete");
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            variant="destructive"
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
