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
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import { buildDocsUrl } from "@/lib/utils";

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
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

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

  const onProjectCreated = useCallback(
    (projectData?: { id?: string }) => {
      const explainer =
        EXPLAINERS_MAP[EXPLAINER_ID.i_created_a_project_now_what];

      toast({
        title: explainer.title,
        description: explainer.description,
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="px-0"
            altText="Log traces to your project"
            key="Log traces to your project"
          >
            <a
              href={buildDocsUrl(explainer.docLink)}
              target="_blank"
              rel="noreferrer"
            >
              Log traces to your project
            </a>
          </ToastAction>,
        ],
      });

      if (projectData?.id) {
        navigate({
          to: "/$workspaceName/projects/$projectId/traces",
          params: {
            projectId: projectData.id,
            workspaceName,
          },
        });
      }
    },
    [navigate, toast, workspaceName],
  );

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate({
        project: {
          id: project!.id,
          description,
        },
      });
    } else {
      createMutate(
        {
          project: {
            name,
            ...(description && { description }),
          },
        },
        {
          onSuccess: onProjectCreated,
        },
      );
    }
  }, [
    createMutate,
    description,
    isEdit,
    name,
    onProjectCreated,
    project,
    updateMutate,
  ]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {!isEdit && (
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[
              EXPLAINER_ID.why_would_i_want_to_create_a_new_project
            ]}
          />
        )}
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
