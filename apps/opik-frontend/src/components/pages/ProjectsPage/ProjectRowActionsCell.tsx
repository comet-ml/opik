import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { Project } from "@/types/projects";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import { DEFAULT_PROJECT_NAME } from "@/constants/projects";

export const ProjectRowActionsCell: React.FunctionComponent<
  CellContext<Project, unknown>
> = ({ row }) => {
  const resetKeyRef = useRef(0);
  const project = row.original;
  const [open, setOpen] = useState<boolean>(false);

  const projectDeleteMutation = useProjectDeleteMutation();

  const deleteProjectHandler = useCallback(() => {
    projectDeleteMutation.mutate({
      projectId: project.id,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project.id]);

  return (
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteProjectHandler}
        title={`Delete ${project.name}`}
        description="Are you sure you want to delete this project?"
        confirmText="Delete project"
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
            disabled={project.name === DEFAULT_PROJECT_NAME}
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
