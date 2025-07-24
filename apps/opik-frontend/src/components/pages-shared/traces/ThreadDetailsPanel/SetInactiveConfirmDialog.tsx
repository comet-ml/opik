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
import { Link } from "@tanstack/react-router";
import { WORKSPACE_PREFERENCE_TYPE } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/types";
import { ExternalLink } from "lucide-react";
import useAppStore from "@/store/AppStore";
import { WORKSPACE_PREFERENCES_QUERY_PARAMS } from "@/components/pages/ConfigurationPage/WorkspacePreferencesTab/constants";

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
  const { activeWorkspaceName: workspaceName } = useAppStore();

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
            Setting this thread as inactive will let you add scores, tags, and
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
                  [WORKSPACE_PREFERENCES_QUERY_PARAMS.EDIT_PREFERENCE]:
                    WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
                }}
                target="_blank"
                rel="noopener noreferrer"
              >
                set a custom timeout
                <ExternalLink className="size-3" />
              </Link>
            </Button>
            to automatically expire the session later.
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
