import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { Project } from "@/types/projects";
import { CellContext } from "@tanstack/react-table";
import AddEditProjectDialog from "@/components/pages/ProjectsPage/AddEditProjectDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

export const ProjectRowActionsCell: React.FC<CellContext<Project, unknown>> = (
  context,
) => {
  const resetKeyRef = useRef(0);
  const project = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useProjectDeleteMutation();

  const deleteProjectHandler = useCallback(() => {
    mutate({
      projectId: project.id,
    });
  }, [project.id, mutate]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <AddEditProjectDialog
        key={`add-${resetKeyRef.current}`}
        project={project}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteProjectHandler}
        title={`Delete ${project.name}`}
        description="Are you sure you want to delete this project?"
        confirmText="Delete project"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5 ">
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
    </CellWrapper>
  );
};
