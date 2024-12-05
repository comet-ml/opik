import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";
import { Project } from "@/types/projects";
import { Textarea } from "@/components/ui/textarea";
import useProjectUpdateMutation from "@/api/projects/useProjectUpdateMutation";

type AddEditProjectDialogProps = {
  project?: Project;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddEditProjectDialog: React.FC<AddEditProjectDialogProps> = ({
  project,
  open,
  setOpen,
}) => {
  const { mutate: createMutate } = useProjectCreateMutation();
  const { mutate: updateMutate } = useProjectUpdateMutation();
  const [name, setName] = useState(project ? project.name : "");
  const [description, setDescription] = useState(
    project ? project.description : "",
  );

  const isEdit = Boolean(project);
  const isValid = Boolean(name.length);
  const title = isEdit ? "Edit project" : "Create a new project";
  const buttonText = isEdit ? "Update project" : "Create project";

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate({
        project: {
          id: project!.id,
          description,
        },
      });
    } else {
      createMutate({
        project: {
          name,
          ...(description && { description }),
        },
      });
    }
  }, [createMutate, description, isEdit, name, project, updateMutate]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="projectName">Name</Label>
          <Input
            id="projectName"
            placeholder="Project name"
            disabled={isEdit}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="projectDescription">Description</Label>
          <Textarea
            id="projectDescription"
            placeholder="Project description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={255}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={submitHandler}>
              {buttonText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditProjectDialog;
