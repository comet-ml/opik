import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useFeedbackDefinitionDeleteMutation from "@/api/feedback-definitions/useFeedbackDefinitionDeleteMutation";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";

const FeedbackDefinitionsRowActionsCell: React.FunctionComponent<
  CellContext<FeedbackDefinition, unknown>
> = ({ row }) => {
  const resetKeyRef = useRef(0);
  const feedbackDefinition = row.original;
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
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <AddEditFeedbackDefinitionDialog
        key={`edit-${resetKeyRef.current}`}
        feedbackDefinition={feedbackDefinition}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteFeedbackDefinitionHandler}
        title="Delete feedback definition"
        description="Are you sure you want to delete this feedback definition?"
        confirmText="Delete feedback definition"
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
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
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

export default FeedbackDefinitionsRowActionsCell;
