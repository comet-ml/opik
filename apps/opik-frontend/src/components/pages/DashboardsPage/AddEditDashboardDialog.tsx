import React, { useCallback, useEffect } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import useDashboardById from "@/api/dashboards/useDashboardById";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import useAppStore from "@/store/AppStore";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";

const formSchema = z.object({
  name: z.string().min(1, "Dashboard name is required"),
  description: z.string().optional(),
});

type AddEditDashboardDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  dashboardId?: string;
};

const AddEditDashboardDialog: React.FunctionComponent<
  AddEditDashboardDialogProps
> = ({ open, setOpen, dashboardId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const projectId = useProjectIdFromURL();
  const isEdit = Boolean(dashboardId);

  const { data: dashboard } = useDashboardById(
    {
      dashboardId: dashboardId!,
      workspaceName,
    },
    {
      enabled: isEdit,
    },
  );

  const createMutation = useDashboardCreateMutation();
  const updateMutation = useDashboardUpdateMutation();

  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  });

  useEffect(() => {
    if (dashboard) {
      form.reset({
        name: dashboard.name,
        description: dashboard.description || "",
      });
    } else {
      form.reset({
        name: "",
        description: "",
      });
    }
  }, [dashboard, form]);

  const isLoading =
    createMutation.isPending || updateMutation.isPending;

  const onSubmit = useCallback(
    (values: z.infer<typeof formSchema>) => {
      const payload = {
        name: values.name,
        description: values.description || undefined,
        type: "custom" as const,
      };

      if (isEdit && dashboardId) {
        updateMutation.mutate(
          {
            dashboardId,
            dashboard: payload,
            workspaceName,
          },
          {
            onSuccess: () => {
              setOpen(false);
              form.reset();
            },
          },
        );
      } else {
        createMutation.mutate(
          {
            dashboard: {
              ...payload,
              project_ids: projectId ? [projectId] : [],
            },
            workspaceName,
          },
          {
            onSuccess: () => {
              setOpen(false);
              form.reset();
            },
          },
        );
      }
    },
    [
      isEdit,
      dashboardId,
      createMutation,
      updateMutation,
      workspaceName,
      projectId,
      setOpen,
      form,
    ],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {isEdit ? "Edit dashboard" : "Create new dashboard"}
          </DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Dashboard name</FormLabel>
                  <FormControl>
                    <Input
                      placeholder="Enter dashboard name"
                      autoComplete="off"
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="description"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Description (optional)</FormLabel>
                  <FormControl>
                    <Textarea
                      placeholder="Describe this dashboard"
                      rows={3}
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setOpen(false)}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={isLoading}>
                {isEdit ? "Update" : "Create"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditDashboardDialog;

