import React, { useCallback, useMemo } from "react";
import { flushSync } from "react-dom";
import { AxiosError, HttpStatusCode } from "axios";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import { ChartLine, FlaskConical, LayoutGridIcon, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
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
import { Textarea } from "@/components/ui/textarea";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { cn } from "@/lib/utils";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import { Dashboard, DASHBOARD_SCOPE, DASHBOARD_TYPE } from "@/types/dashboard";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import {
  generateEmptyDashboard,
  regenerateAllIds,
} from "@/lib/dashboard/utils";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import { useDashboardStore } from "@/store/DashboardStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DISABLED_EXPERIMENTS_TOOLTIP } from "@/constants/permissions";
import CardSelector, {
  CardOption,
} from "@/components/shared/CardSelector/CardSelector";

const DASHBOARD_TYPE_OPTIONS: CardOption[] = [
  {
    value: DASHBOARD_TYPE.MULTI_PROJECT,
    label: "Multi-project dashboard",
    description:
      "Monitor performance, cost, and usage across multiple projects in your workspace.",
    icon: <LayoutGridIcon className="size-4" />,
    iconColor: "text-chart-red",
  },
  {
    value: DASHBOARD_TYPE.EXPERIMENTS,
    label: "Experiments dashboard",
    description:
      "Track experiment results and quality metrics across your workspace.",
    icon: <FlaskConical className="size-4" />,
    iconColor: "text-chart-green",
  },
];

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
  dashboardType: z.nativeEnum(DASHBOARD_TYPE),
});

type DashboardFormData = z.infer<typeof DashboardFormSchema>;

export type DashboardDialogMode = "create" | "edit" | "clone";

type AddEditCloneDashboardDialogProps = {
  mode: DashboardDialogMode;
  dashboard?: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
  onCreateSuccess?: (dashboardId: string) => void;
  onEditSuccess?: () => void;
  navigateOnCreate?: boolean;
  dashboardType?: DASHBOARD_TYPE;
  dashboardScope?: DASHBOARD_SCOPE;
};

const AddEditCloneDashboardDialog: React.FC<
  AddEditCloneDashboardDialogProps
> = ({
  mode,
  dashboard,
  open,
  setOpen,
  onCreateSuccess,
  onEditSuccess,
  navigateOnCreate = true,
  dashboardType,
  dashboardScope,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  const isCreateMode = mode === "create";

  const { mutate: createMutate, isPending: isCreating } =
    useDashboardCreateMutation({
      skipDefaultError: true,
    });
  const { mutate: updateMutate, isPending: isUpdating } =
    useDashboardUpdateMutation({
      skipDefaultError: true,
    });

  const isPending = isCreating || isUpdating;

  const getInitialName = () => {
    if (mode === "clone") {
      return `${dashboard!.name} (Copy)`;
    }

    return dashboard?.name || "";
  };

  const form = useForm<DashboardFormData>({
    resolver: zodResolver(DashboardFormSchema),
    mode: "onChange",
    defaultValues: {
      name: getInitialName(),
      description: dashboard?.description || "",
      dashboardType:
        dashboardType ?? dashboard?.type ?? DASHBOARD_TYPE.MULTI_PROJECT,
    },
  });

  const config = {
    create: {
      title: "Create dashboard",
      description:
        "Build a dashboard to monitor project performance or experiment quality across your workspace.",
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
      const dashboardId = dashboardData?.id;
      if (dashboardId) {
        flushSync(() => {
          onCreateSuccess?.(dashboardId);
        });

        if (navigateOnCreate) {
          navigate({
            to: "/$workspaceName/dashboards/$dashboardId",
            params: {
              dashboardId,
              workspaceName,
            },
          });
        }
      }
    },
    [navigate, workspaceName, onCreateSuccess, navigateOnCreate],
  );

  const showCreatedToast = useCallback(() => {
    toast({
      title: "Dashboard created",
      description: "Start customizing it by adding widgets.",
      actions: [
        <ToastAction
          variant="link"
          size="sm"
          className="px-0"
          altText="Add your first widget"
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
          Add your first widget
        </ToastAction>,
      ],
    });
  }, [toast]);

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
              onEditSuccess?.();
              setOpen(false);
            },
            onError: (error: AxiosError) =>
              handleMutationError(error, "update"),
          },
        );
      } else {
        let dashboardConfig;

        if (mode === "create") {
          dashboardConfig = generateEmptyDashboard();
        } else if (mode === "clone") {
          dashboardConfig = regenerateAllIds(dashboard!.config);
        }

        createMutate(
          {
            dashboard: {
              name: values.name,
              description: values.description || "",
              config: dashboardConfig,
              type: mode === "create" ? values.dashboardType : dashboard?.type,
              scope:
                mode === "create"
                  ? dashboardScope ?? DASHBOARD_SCOPE.WORKSPACE
                  : dashboard?.scope,
            },
          },
          {
            onSuccess: (data) => {
              onDashboardCreated(data);
              setOpen(false);
              if (mode === "create") {
                showCreatedToast();
              }
            },
            onError: (error: AxiosError) => handleMutationError(error, mode),
          },
        );
      }
    },
    [
      mode,
      updateMutate,
      dashboard,
      onEditSuccess,
      setOpen,
      handleMutationError,
      createMutate,
      onDashboardCreated,
      showCreatedToast,
      dashboardScope,
    ],
  );

  const dashboardTypeOptions = useMemo<CardOption[]>(
    () =>
      DASHBOARD_TYPE_OPTIONS.map((option) =>
        option.value === DASHBOARD_TYPE.EXPERIMENTS && !canViewExperiments
          ? {
              ...option,
              disabled: true,
              disabledTooltip: DISABLED_EXPERIMENTS_TOOLTIP,
            }
          : option,
      ),
    [canViewExperiments],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
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
              id="dashboard-form"
              className="flex flex-col gap-4"
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
                          placeholder="Dashboard name"
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

              {isCreateMode && !dashboardType && (
                <FormField
                  control={form.control}
                  name="dashboardType"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Type</FormLabel>
                      <CardSelector
                        value={field.value}
                        onChange={field.onChange}
                        options={dashboardTypeOptions}
                      />
                    </FormItem>
                  )}
                />
              )}

              <Accordion
                type="multiple"
                defaultValue={!isCreateMode ? ["description"] : []}
                className="border-t"
              >
                <AccordionItem value="description">
                  <AccordionTrigger className="h-11 py-1.5">
                    Description
                  </AccordionTrigger>
                  <AccordionContent className="px-3">
                    <Textarea
                      {...form.register("description")}
                      placeholder="Dashboard description"
                      maxLength={255}
                    />
                  </AccordionContent>
                </AccordionItem>
              </Accordion>
            </form>
          </Form>
        </DialogAutoScrollBody>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" disabled={isPending}>
              Cancel
            </Button>
          </DialogClose>

          <Button type="submit" form="dashboard-form" disabled={isPending}>
            {isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
            {config.buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditCloneDashboardDialog;
