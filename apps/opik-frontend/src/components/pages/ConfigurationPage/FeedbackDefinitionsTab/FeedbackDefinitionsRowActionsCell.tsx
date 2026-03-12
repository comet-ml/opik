import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy, MoreHorizontal, Pencil, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useFeedbackDefinitionDeleteMutation from "@/api/feedback-definitions/useFeedbackDefinitionDeleteMutation";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const FeedbackDefinitionsRowActionsCell: React.FunctionComponent<
  CellContext<FeedbackDefinition, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const feedbackDefinition = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const feedbackDefinitionDeleteMutation =
    useFeedbackDefinitionDeleteMutation();

  const deleteFeedbackDefinitionHandler = useCallback(() => {
    feedbackDefinitionDeleteMutation.mutate({
      feedbackDefinitionId: feedbackDefinition.id,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackDefinition.id]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <AddEditFeedbackDefinitionDialog
        key={`edit-${resetKeyRef.current}`}
        feedbackDefinition={feedbackDefinition}
        open={open === 2 || open === 3}
        setOpen={setOpen}
        mode={open === 2 ? "edit" : "clone"}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteFeedbackDefinitionHandler}
        title="Delete feedback definition"
        description="This action can’t be undone. Existing scored traces won’t be affected. Are you sure you want to continue?"
        confirmText="Delete feedback definition"
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
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(3);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Copy className="mr-2 size-4" />
            Clone
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
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

export default FeedbackDefinitionsRowActionsCell;
