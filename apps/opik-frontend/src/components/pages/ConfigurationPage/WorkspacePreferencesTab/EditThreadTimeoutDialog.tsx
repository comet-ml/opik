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
import useAppStore from "@/store/AppStore";
import EditThreadTimeoutForm from "./EditThreadTimeoutForm";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import { useNavigate } from "@tanstack/react-router";

type EditThreadTimeoutDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  defaultValue: string;
};

const EDIT_THREAD_TIMEOUT_FORM_ID = "editThreadTimeoutForm";

const EditThreadTimeoutDialog: React.FC<EditThreadTimeoutDialogProps> = ({
  open,
  setOpen,
  defaultValue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const navigate = useNavigate();

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
          onSubmit={(data) => {
            console.log("submit", data);

            toast({
              title: "Thread timeout updated",
              description: `All new threads will now be automatically set to inactive after ${data.timeout} minutes of inactivity. Once the thread is inactive, you can add feedback, comments, and tags.`,
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
                    setOpen(false);
                  }}
                >
                  Go to projects
                </ToastAction>,
              ],
            });
          }}
          formId={EDIT_THREAD_TIMEOUT_FORM_ID}
        />

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
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
