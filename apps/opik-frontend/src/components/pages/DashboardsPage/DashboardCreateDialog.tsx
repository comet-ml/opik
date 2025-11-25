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
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import { Dashboard } from "@/types/dashboard";
import { Textarea } from "@/components/ui/textarea";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { generateEmptyDashboard } from "@/lib/dashboard/utils";

type DashboardCreateDialogProps = {
  dashboard?: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const DashboardCreateDialog: React.FC<DashboardCreateDialogProps> = ({
  dashboard,
  open,
  setOpen,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { mutate: createMutate } = useDashboardCreateMutation();
  const { mutate: updateMutate } = useDashboardUpdateMutation();
  const [name, setName] = useState(dashboard ? dashboard.name : "");
  const [description, setDescription] = useState(
    dashboard ? dashboard.description ?? "" : "",
  );

  const isEdit = Boolean(dashboard);
  const isValid = Boolean(name.length);
  const title = isEdit ? "Edit dashboard" : "Create a new dashboard";
  const buttonText = isEdit ? "Update dashboard" : "Create dashboard";

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
    if (isEdit) {
      updateMutate({
        dashboard: {
          id: dashboard!.id,
          name,
          description,
        },
      });
    } else {
      const emptyDashboard = generateEmptyDashboard();
      createMutate(
        {
          dashboard: {
            name,
            description,
            config: emptyDashboard,
          },
        },
        {
          onSuccess: onDashboardCreated,
        },
      );
    }
    setOpen(false);
  }, [
    createMutate,
    description,
    isEdit,
    name,
    onDashboardCreated,
    dashboard,
    updateMutate,
    setOpen,
  ]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {!isEdit && (
            <DialogDescription>
              Build a dashboard to track performance, cost, or other key
              metrics. You&apos;ll be able to add widgets and visualizations
              after creation.
            </DialogDescription>
          )}
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
              {buttonText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DashboardCreateDialog;
