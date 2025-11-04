import React from "react";
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
import EditThreadTimeoutForm, {
  EditThreadTimeoutFormValues,
} from "./EditThreadTimeoutForm";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { formatIso8601Duration } from "@/lib/date";

type EditThreadTimeoutDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  defaultValue: string;
  onSubmit: (values: EditThreadTimeoutFormValues) => void;
};

const EDIT_THREAD_TIMEOUT_FORM_ID = "editThreadTimeoutForm";

const EditThreadTimeoutDialog: React.FC<EditThreadTimeoutDialogProps> = ({
  open,
  setOpen,
  defaultValue,
  onSubmit,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const navigate = useNavigate();

  const handleSubmit = (config: EditThreadTimeoutFormValues) => {
    onSubmit(config);
    setOpen(false);

    const formattedDuration = formatIso8601Duration(
      config.timeout_to_mark_thread_as_inactive,
    );

    toast({
      title: "Thread timeout updated",
      description: `All new threads will now be automatically set to inactive after ${formattedDuration} of inactivity. Once the thread is inactive, you can add feedback, comments, and tags.`,
      actions: [
        <ToastAction
          variant="link"
          size="sm"
          className="px-0"
          altText="Go to project"
          key="Go to project"
          onClick={() => {
            navigate({
              to: "/$workspaceName/projects",
              params: {
                workspaceName,
              },
            });
          }}
        >
          Go to projects
        </ToastAction>,
      ],
    });
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Edit thread timeout</DialogTitle>
          <DialogDescription>
            Set how long a thread stays Active before switching to Inactive.
            Sending a new message reactivates the thread and clears all scores,
            tags, and comments.
          </DialogDescription>
        </DialogHeader>

        <EditThreadTimeoutForm
          defaultValue={defaultValue}
          onSubmit={handleSubmit}
          formId={EDIT_THREAD_TIMEOUT_FORM_ID}
        />

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            variant="default"
            form={EDIT_THREAD_TIMEOUT_FORM_ID}
          >
            Update timeout
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditThreadTimeoutDialog;
