import React, { useCallback } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import { ChartLine, Loader2 } from "lucide-react";

import { Button } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Input } from "@/ui/input";
import { cn } from "@/lib/utils";
import { useToast } from "@/ui/use-toast";
import { ToastAction } from "@/ui/toast";
import useInsightsViewCreateMutation from "@/api/insights-views/useInsightsViewCreateMutation";
import useInsightsViewUpdateMutation from "@/api/insights-views/useInsightsViewUpdateMutation";
import {
  generateEmptyDashboard,
  regenerateAllIds,
} from "@/lib/dashboard/utils";
import { Dashboard, DASHBOARD_TYPE } from "@/types/dashboard";
import { useDashboardStore } from "@/store/DashboardStore";

export type InsightsViewDialogMode = "create" | "edit" | "clone";

interface InsightsViewDialogProps {
  mode: InsightsViewDialogMode;
  dashboard?: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
  onCreateSuccess?: (dashboardId: string) => void;
}

const MODE_CONFIG = {
  create: {
    title: "Create view",
    buttonText: "Create view",
  },
  edit: {
    title: "Edit view",
    buttonText: "Rename view",
  },
  clone: {
    title: "Duplicate view",
    buttonText: "Duplicate view",
  },
} as const;

const FormSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required")
    .max(100, "Name must be less than 100 characters")
    .trim(),
});

type FormData = z.infer<typeof FormSchema>;

const InsightsViewDialog: React.FC<InsightsViewDialogProps> = ({
  mode,
  dashboard,
  open,
  setOpen,
  onCreateSuccess,
}) => {
  const { toast } = useToast();
  const config = MODE_CONFIG[mode];

  const { mutate: createMutate, isPending: isCreating } =
    useInsightsViewCreateMutation({ skipDefaultError: true });
  const { mutate: updateMutate, isPending: isUpdating } =
    useInsightsViewUpdateMutation({ skipDefaultError: true });

  const isPending = isCreating || isUpdating;

  const getInitialName = () => {
    if (mode === "clone") return `${dashboard!.name} (Copy)`;
    return dashboard?.name || "";
  };

  const form = useForm<FormData>({
    resolver: zodResolver(FormSchema),
    mode: "onChange",
    defaultValues: { name: getInitialName() },
  });

  const showToast = useCallback(
    (toastMode: InsightsViewDialogMode) => {
      const toastConfigs = {
        create: {
          title: "View created",
          description: "Start customizing it by adding widgets",
          actionLabel: "Add your first widget",
        },
        clone: {
          title: "View created",
          description: "Start customizing it by adding or editing widgets",
          actionLabel: "Add a widget",
        },
        edit: {
          title: "View updated",
          description: "Your changes have been saved.",
          actionLabel: null,
        },
      } as const;

      const toastConfig = toastConfigs[toastMode];

      toast({
        title: toastConfig.title,
        description: toastConfig.description,
        actions: toastConfig.actionLabel
          ? [
              <ToastAction
                variant="link"
                size="sm"
                className="px-0"
                altText={toastConfig.actionLabel}
                key="add-widget"
                onClick={() => {
                  const { onAddEditWidgetCallback, sections } =
                    useDashboardStore.getState();
                  if (onAddEditWidgetCallback && sections.length > 0) {
                    onAddEditWidgetCallback({ sectionId: sections[0].id });
                  }
                }}
              >
                <ChartLine className="mr-1.5 size-3.5" />
                {toastConfig.actionLabel}
              </ToastAction>,
            ]
          : undefined,
      });
    },
    [toast],
  );

  const handleMutationError = useCallback(
    (error: AxiosError) => {
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
          title: "Error saving view",
          description: message || "Failed to save view",
          variant: "destructive",
        });
      }
    },
    [form, toast],
  );

  const onSubmit = useCallback(
    (values: FormData) => {
      if (mode === "edit") {
        updateMutate(
          {
            dashboard: {
              id: dashboard!.id,
              name: values.name,
            },
          },
          {
            onSuccess: () => {
              setOpen(false);
              showToast("edit");
            },
            onError: (error: AxiosError) => handleMutationError(error),
          },
        );
      } else {
        const dashboardConfig =
          mode === "create"
            ? generateEmptyDashboard()
            : regenerateAllIds(dashboard!.config);

        createMutate(
          {
            dashboard: {
              name: values.name,
              description: "",
              config: dashboardConfig,
              type:
                mode === "create"
                  ? DASHBOARD_TYPE.MULTI_PROJECT
                  : dashboard?.type,
            },
          },
          {
            onSuccess: (data) => {
              const dashboardId = data?.id;
              if (dashboardId) {
                onCreateSuccess?.(dashboardId);
              }
              setOpen(false);
              showToast(mode);
            },
            onError: (error: AxiosError) => handleMutationError(error),
          },
        );
      }
    },
    [
      mode,
      dashboard,
      updateMutate,
      createMutate,
      setOpen,
      onCreateSuccess,
      showToast,
      handleMutationError,
    ],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>{config.title}</DialogTitle>
          {mode === "create" && (
            <DialogDescription>
              Create a view to track performance and quality for this project.
              Widgets will use the current project automatically.
            </DialogDescription>
          )}
          {mode === "clone" && dashboard && (
            <DialogDescription>
              {`Create a copy of ${dashboard.name} to customize it without affecting the original.`}
            </DialogDescription>
          )}
        </DialogHeader>

        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(onSubmit)}
            id="insights-view-form"
            className="flex flex-col gap-4 py-4"
          >
            <FormField
              control={form.control}
              name="name"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, ["name"]);

                return (
                  <FormItem>
                    <FormLabel>Name</FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        placeholder="View name"
                        className={cn({
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
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
                );
              }}
            />
          </form>
        </Form>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" disabled={isPending}>
              Cancel
            </Button>
          </DialogClose>

          <Button type="submit" form="insights-view-form" disabled={isPending}>
            {isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
            {config.buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default InsightsViewDialog;
