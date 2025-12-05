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
import { Dashboard, TEMPLATE_ID } from "@/types/dashboard";
import { Textarea } from "@/components/ui/textarea";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import {
  generateEmptyDashboard,
  regenerateAllIds,
} from "@/lib/dashboard/utils";
import { useToast } from "@/components/ui/use-toast";
import {
  DASHBOARD_TEMPLATES,
  TEMPLATE_OPTIONS_ORDER,
} from "@/lib/dashboard/templates";
import DashboardTemplateSelector from "@/components/pages-shared/dashboards/DashboardTemplateSelector/DashboardTemplateSelector";
import { DASHBOARD_CREATION_TYPE } from "@/types/dashboard";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { DialogAutoScrollBody } from "@/components/ui/dialog";

const DashboardFormSchema = z
  .object({
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
    creationType: z
      .enum([DASHBOARD_CREATION_TYPE.EMPTY, DASHBOARD_CREATION_TYPE.TEMPLATE])
      .default(DASHBOARD_CREATION_TYPE.EMPTY),
    templateId: z.string().optional(),
    projectId: z.string().optional(),
  })
  .refine(
    (data) => {
      // projectId is required only when creationType is "template"
      return !(
        data.creationType === DASHBOARD_CREATION_TYPE.TEMPLATE &&
        !data.projectId
      );
    },
    {
      message: "Project is required for template creation",
      path: ["projectId"],
    },
  )
  .refine(
    (data) => {
      // templateId is required only when creationType is "template"
      return !(
        data.creationType === DASHBOARD_CREATION_TYPE.TEMPLATE &&
        !data.templateId
      );
    },
    {
      message: "Template is required",
      path: ["templateId"],
    },
  );

type DashboardFormData = z.infer<typeof DashboardFormSchema>;

export type DashboardDialogMode = "create" | "edit" | "clone" | "save_as";

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
    if (mode === "clone" || mode === "save_as") {
      return `${dashboard!.name} (Copy)`;
    }

    return dashboard?.name || "";
  };

  const form = useForm<DashboardFormData>({
    resolver: zodResolver(DashboardFormSchema),
    mode: "onTouched",
    defaultValues: {
      name: getInitialName(),
      description: dashboard?.description || "",
      creationType: DASHBOARD_CREATION_TYPE.EMPTY,
      templateId: undefined,
      projectId: undefined,
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
    save_as: {
      title: "Save dashboard as",
      description: null,
      buttonText: "Save as new dashboard",
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
        // "create", "clone", and "save_as" use createMutate
        let dashboardConfig;

        if (mode === "create") {
          if (
            values.creationType === DASHBOARD_CREATION_TYPE.TEMPLATE &&
            values.templateId
          ) {
            // Create from template
            const template =
              DASHBOARD_TEMPLATES[values.templateId as TEMPLATE_ID];
            if (template) {
              dashboardConfig = regenerateAllIds(template.config);
              // Set projectIds in config with selected project as first item
              dashboardConfig.config.projectIds = values.projectId
                ? [values.projectId]
                : [];
            } else {
              dashboardConfig = generateEmptyDashboard();
            }
          } else {
            // Create empty dashboard
            dashboardConfig = generateEmptyDashboard();
          }
        } else if (mode === "save_as") {
          // Save as mode: Regenerate IDs from current config
          dashboardConfig = regenerateAllIds(dashboard!.config);
        } else {
          // Clone mode
          dashboardConfig = regenerateAllIds(dashboard!.config);
        }

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
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>{config.title}</DialogTitle>
          {config.showDescription && (
            <DialogDescription>{config.description}</DialogDescription>
          )}
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              onSubmit={form.handleSubmit(onSubmit)}
              className="flex flex-col gap-2 pb-4"
              id="dashboard-form"
            >
              {/* Name field */}
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem className="pb-2">
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

              {/* Type and Template selection (only for create mode) */}
              {mode === "create" && (
                <DashboardTemplateSelector
                  control={form.control}
                  onCreationTypeChange={(value) => {
                    if (value === DASHBOARD_CREATION_TYPE.EMPTY) {
                      // Reset template and project when switching to empty
                      form.setValue("templateId", undefined);
                      form.setValue("projectId", undefined);
                    } else if (value === DASHBOARD_CREATION_TYPE.TEMPLATE) {
                      // Preselect the first template when switching to template mode (only if none is selected)
                      if (
                        TEMPLATE_OPTIONS_ORDER.length > 0 &&
                        !form.getValues("templateId")
                      ) {
                        form.setValue("templateId", TEMPLATE_OPTIONS_ORDER[0]);
                      }
                    }
                  }}
                />
              )}

              {/* Description accordion at the bottom */}
              <div className="flex flex-col gap-2 border-t border-border pb-4 pt-2">
                <Accordion
                  type="multiple"
                  defaultValue={
                    dashboard?.description ? ["description"] : undefined
                  }
                >
                  <AccordionItem value="description">
                    <AccordionTrigger>Description</AccordionTrigger>
                    <AccordionContent>
                      <Textarea
                        {...form.register("description")}
                        placeholder="Dashboard description"
                        maxLength={255}
                      />
                    </AccordionContent>
                  </AccordionItem>
                </Accordion>
              </div>
            </form>
          </Form>
        </DialogAutoScrollBody>
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
