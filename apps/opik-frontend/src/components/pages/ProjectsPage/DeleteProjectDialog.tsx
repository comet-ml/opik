import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Project } from "@/types/projects";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import useAppStore from "@/store/AppStore";

type DeleteProjectDialogProps = {
  project: Project;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const DeleteProjectDialog: React.FunctionComponent<
  DeleteProjectDialogProps
> = ({ project, open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [validation, setValidation] = useState<string>("");
  const projectDeleteMutation = useProjectDeleteMutation();

  const isValid = validation === project.name;

  const deleteProject = useCallback(() => {
    projectDeleteMutation.mutate({
      projectId: project.id,
      workspaceName,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project.id, workspaceName]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{`Delete ${project.name}`}</DialogTitle>
          <DialogDescription>
            Once a project is deleted, all data associated with it is deleted
            and cannot be recovered.
          </DialogDescription>
        </DialogHeader>
        <div className="flex min-w-1 max-w-full flex-col gap-4">
          <Label htmlFor="projectName">
            {`To validation, type "${project.name}"`}
          </Label>
          <Input
            id="projectName"
            placeholder=""
            value={validation}
            onChange={(event) => setValidation(event.target.value)}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={deleteProject}>
              Delete project
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DeleteProjectDialog;
