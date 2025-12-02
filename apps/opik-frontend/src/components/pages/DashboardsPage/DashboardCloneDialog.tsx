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
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import { Dashboard } from "@/types/dashboard";
import { Textarea } from "@/components/ui/textarea";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type DashboardCloneDialogProps = {
  dashboard: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const DashboardCloneDialog: React.FC<DashboardCloneDialogProps> = ({
  dashboard,
  open,
  setOpen,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { mutate: createMutate } = useDashboardCreateMutation();
  const [name, setName] = useState(`${dashboard.name} (Copy)`);
  const [description, setDescription] = useState(dashboard.description ?? "");

  const isValid = Boolean(name.length);

  const onDashboardCreated = useCallback(
    (dashboardData?: { id?: string }) => {
      if (dashboardData?.id) {
        navigate({
          to: "/$workspaceName/dashboards/$dashboardId",
          params: {
            dashboardId: dashboardData.id,
            workspaceName,
          },
        });
      }
    },
    [navigate, workspaceName],
  );

  const submitHandler = useCallback(() => {
    createMutate(
      {
        dashboard: {
          name,
          description,
          config: dashboard.config,
        },
      },
      {
        onSuccess: onDashboardCreated,
      },
    );
    setOpen(false);
  }, [createMutate, description, name, onDashboardCreated, dashboard, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Clone dashboard</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="dashboardName">Name</Label>
          <Input
            id="dashboardName"
            placeholder="Dashboard name"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="dashboardDescription">Description</Label>
          <Textarea
            id="dashboardDescription"
            placeholder="Dashboard description"
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
              Clone dashboard
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DashboardCloneDialog;
