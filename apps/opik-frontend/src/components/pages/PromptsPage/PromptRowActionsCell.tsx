import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Prompt } from "@/types/prompts";
import usePromptDeleteMutation from "@/api/prompts/usePromptDeleteMutation";
import AddEditPromptDialog from "@/components/pages/PromptsPage/AddEditPromptDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const EDIT_KEY = 1;
const DELETE_KEY = 2;

export const PromptRowActionsCell: React.FunctionComponent<
  CellContext<Prompt, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const prompt = context.row.original;
  const [open, setOpen] = useState<number | boolean>(false);

  const promptDeleteMutation = usePromptDeleteMutation();

  const deletePromptHandler = useCallback(() => {
    promptDeleteMutation.mutate({
      promptId: prompt.id,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prompt.id]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <AddEditPromptDialog
        key={`edit-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        prompt={prompt}
      />

      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={deletePromptHandler}
        title={`Delete ${prompt.name}`}
        description="Deleting a prompt will also remove all associated commits. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete prompt"
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
              setOpen(EDIT_KEY);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>

          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setOpen(DELETE_KEY);
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
