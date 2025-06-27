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
import useThreadCloseStatusMutation from "@/api/traces/useThreadCloseStatusMutation";

type SetInactiveConfirmDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  threadId: string;
  projectId: string;
};

const SetInactiveConfirmDialog: React.FunctionComponent<
  SetInactiveConfirmDialogProps
> = ({ open, setOpen, threadId, projectId }) => {
  const { mutate: setThreadInactive, isPending } =
    useThreadCloseStatusMutation();

  const onConfirm = () => {
    setThreadInactive(
      {
        threadId,
        projectId,
      },
      {
        onSettled: () => {
          setOpen(false);
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Set as inactive</DialogTitle>
          <DialogDescription>
            {/* TODO: Uncomment once Online eval is ready */}
            {/* Setting this thread as inactive will let you add scores, tags, and
            comments. Online evaluation rules will also run. <br /> <br />
            If you send a new message, the thread will become active again, and
            all feedback will be cleared. You can also
            <Button
              size="sm"
              variant="link"
              className="inline-flex h-auto gap-0.5 px-1"
              asChild
            >
              <Link
                to="/$workspaceName/configuration"
                params={{ workspaceName }}
                search={{
                  tab: "workspace-preferences",
                  editPreference: WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
                }}
                target="_blank"
                rel="noopener noreferrer"
              >
                set a custom timeout
                <ExternalLink className="size-3" />
              </Link>
            </Button>
            to automatically expire the session later. */}
            Marking this thread as inactive will allow you to add feedback
            scores. <br />
            If a new message is sent, the thread will automatically become
            active again, and any feedback will be cleared.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            type="submit"
            variant="default"
            onClick={onConfirm}
            disabled={isPending}
          >
            Set as inactive
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SetInactiveConfirmDialog;
