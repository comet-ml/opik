import React, { useCallback } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import { Loader2 } from "lucide-react";
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
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import { Dashboard } from "@/types/dashboard";
import { Textarea } from "@/components/ui/textarea";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { generateEmptyDashboard } from "@/lib/dashboard/utils";
import { useToast } from "@/components/ui/use-toast";

const DashboardFormSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required")
    .max(100, "Name must be less than 100 characters")
    .trim(),
  description: z
    .string()
    .max(255, "Description must be less than 255 characters")
    .optional()
    .or(z.literal("")),
});

type DashboardFormData = z.infer<typeof DashboardFormSchema>;

export type DashboardDialogMode = "create" | "edit" | "clone";

type AddEditCloneDashboardDialogProps = {
  mode: DashboardDialogMode;
  dashboard?: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddEditCloneDashboardDialog: React.FC<
  AddEditCloneDashboardDialogProps
> = ({ mode, dashboard, open, setOpen }) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const { mutate: createMutate, isPending: isCreating } =
    useDashboardCreateMutation({
      skipDefaultError: true,
    });
  const { mutate: updateMutate, isPending: isUpdating } =
    useDashboardUpdateMutation({
      skipDefaultError: true,
    });

  const isPending = isCreating || isUpdating;

  // Initial values based on mode
  const getInitialName = () => {
    if (mode === "clone") return `${dashboard!.name} (Copy)`;
    return dashboard?.name || "";
  };

  const form = useForm<DashboardFormData>({
    resolver: zodResolver(DashboardFormSchema),
    mode: "onTouched",
    defaultValues: {
      name: getInitialName(),
      description: dashboard?.description || "",
    },
  });

  // UI configuration based on mode
  const config = {
    create: {
      title: "Create a new dashboard",
      description:
        "Build a dashboard to track performance, cost, or other key metrics. You'll be able to add widgets and visualizations after creation.",
      buttonText: "Create dashboard",
      showDescription: true,
    },
    edit: {
      title: "Edit dashboard",
      description: null,
      buttonText: "Update dashboard",
      showDescription: false,
    },
    clone: {
      title: "Clone dashboard",
      description: null,
      buttonText: "Clone dashboard",
      showDescription: false,
    },
  }[mode];

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

  const handleMutationError = useCallback(
    (error: AxiosError, action: string) => {
      const statusCode = get(error, ["response", "status"]);
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      if (statusCode === HttpStatusCode.Conflict) {
        form.setError("name", {
          type: "server",
          message: "This name already exists",
        });
      } else {
        toast({
          title: `Error saving dashboard`,
          description: message || `Failed to ${action} dashboard`,
          variant: "destructive",
        });
      }
    },
    [form, toast],
  );

  const onSubmit = useCallback(
    (values: DashboardFormData) => {
      if (mode === "edit") {
        updateMutate(
          {
            dashboard: {
              id: dashboard!.id,
              name: values.name,
              description: values.description || "",
            },
          },
          {
            onSuccess: () => {
              setOpen(false);
            },
            onError: (error: AxiosError) =>
              handleMutationError(error, "update"),
          },
        );
      } else {
        // Both "create" and "clone" use createMutate
        const dashboardConfig =
          mode === "create" ? generateEmptyDashboard() : dashboard!.config;

        createMutate(
          {
            dashboard: {
              name: values.name,
              description: values.description || "",
              config: dashboardConfig,
            },
          },
          {
            onSuccess: onDashboardCreated,
            onError: (error: AxiosError) => handleMutationError(error, mode),
          },
        );
      }
    },
    [
      mode,
      dashboard,
      createMutate,
      updateMutate,
      onDashboardCreated,
      handleMutationError,
      setOpen,
    ],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{config.title}</DialogTitle>
          {config.showDescription && (
            <DialogDescription>{config.description}</DialogDescription>
          )}
        </DialogHeader>
        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(onSubmit)}
            className="space-y-4"
            id="dashboard-form"
          >
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input
                      {...field}
                      placeholder="Dashboard name"
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          form.handleSubmit(onSubmit)();
                        }
                      }}
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
                  <FormLabel>Description</FormLabel>
                  <FormControl>
                    <Textarea
                      {...field}
                      placeholder="Dashboard description"
                      maxLength={255}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </form>
        </Form>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" disabled={isPending}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            type="submit"
            form="dashboard-form"
            disabled={!form.formState.isValid || isPending}
          >
            {isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
            {config.buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditCloneDashboardDialog;
