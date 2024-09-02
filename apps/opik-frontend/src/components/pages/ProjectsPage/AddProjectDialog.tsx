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
import useAppStore from "@/store/AppStore";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";

type AddProjectDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddProjectDialog: React.FunctionComponent<AddProjectDialogProps> = ({
  open,
  setOpen,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const projectCreateMutation = useProjectCreateMutation();
  const [name, setName] = useState<string>("");

  const isValid = Boolean(name.length);

  const createProject = useCallback(() => {
    projectCreateMutation.mutate({
      project: {
        name,
      },
      workspaceName,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, workspaceName]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Create a new project</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="projectName">Name</Label>
          <Input
            id="projectName"
            placeholder="Project name"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={createProject}>
              Create project
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddProjectDialog;
