import React, { useCallback, useState, useEffect } from "react";
import { flushSync } from "react-dom";
import { AxiosError, HttpStatusCode } from "axios";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import { Loader2, ChevronLeft } from "lucide-react";
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
import { Form } from "@/components/ui/form";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import {
  Dashboard,
  TEMPLATE_TYPE,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { Filters } from "@/types/filters";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import {
  generateEmptyDashboard,
  regenerateAllIds,
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
  DEFAULT_MAX_EXPERIMENTS,
  isValidIntegerInRange,
} from "@/lib/dashboard/utils";
import { useToast } from "@/components/ui/use-toast";
import { DASHBOARD_TEMPLATES } from "@/lib/dashboard/templates";
import { DialogAutoScrollBody } from "@/components/ui/dialog";
import useProjectsList from "@/api/projects/useProjectsList";
import DashboardDialogSelectStep from "./DashboardDialogSelectStep";
import DashboardDialogDetailsStep from "./DashboardDialogDetailsStep";

enum DialogStep {
  SELECT = "select",
  DETAILS = "details",
}

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
    projectId: z.string().optional(),
    experimentIds: z.array(z.string()).optional(),
    templateType: z.string().optional(),
    experimentDataSource: z.nativeEnum(EXPERIMENT_DATA_SOURCE).optional(),
    experimentFilters: FiltersArraySchema.optional(),
    maxExperimentsCount: z.string().optional(),
  })
  .refine(
    (data) => {
      if (
        data.experimentDataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP
      ) {
        return isValidIntegerInRange(
          data.maxExperimentsCount || "",
          MIN_MAX_EXPERIMENTS,
          MAX_MAX_EXPERIMENTS,
        );
      }
      return true;
    },
    {
      message: `Max experiments to load is required and must be between ${MIN_MAX_EXPERIMENTS} and ${MAX_MAX_EXPERIMENTS}`,
      path: ["maxExperimentsCount"],
    },
  );

type DashboardFormData = z.infer<typeof DashboardFormSchema>;

export type DashboardDialogMode = "create" | "edit" | "clone" | "save_as";

type AddEditCloneDashboardDialogProps = {
  mode: DashboardDialogMode;
  dashboard?: Dashboard;
  open: boolean;
  setOpen: (open: boolean) => void;
  onCreateSuccess?: (dashboardId: string) => void;
  onEditSuccess?: () => void;
  navigateOnCreate?: boolean;
  defaultProjectId?: string;
  defaultExperimentIds?: string[];
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
  defaultProjectId,
  defaultExperimentIds,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const isCreateMode = mode === "create";
  const [currentStep, setCurrentStep] = useState<DialogStep>(
    isCreateMode ? DialogStep.SELECT : DialogStep.DETAILS,
  );

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
    if (mode === "clone" || mode === "save_as") {
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
      projectId: defaultProjectId,
      experimentIds: defaultExperimentIds || [],
      templateType: undefined,
      experimentDataSource: EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
      experimentFilters: [],
      maxExperimentsCount: String(DEFAULT_MAX_EXPERIMENTS),
    },
  });

  const shouldFetchLastModifiedProject =
    !defaultProjectId && open && isCreateMode;

  const { data: projectsData } = useProjectsList(
    {
      workspaceName,
      sorting: [
        {
          desc: true,
          id: "last_updated_trace_at",
        },
      ],
      page: 1,
      size: 1,
    },
    {
      enabled: shouldFetchLastModifiedProject,
    },
  );

  const lastModifiedProject = projectsData?.content?.[0];

  useEffect(() => {
    if (
      shouldFetchLastModifiedProject &&
      lastModifiedProject?.id &&
      isEmpty(form.getValues("projectId"))
    ) {
      form.setValue("projectId", lastModifiedProject.id);
    }
  }, [shouldFetchLastModifiedProject, lastModifiedProject?.id, form]);

  const handleSelectOption = (templateType: string) => {
    setCurrentStep(DialogStep.DETAILS);
    form.setValue("templateType", templateType, {
      shouldValidate: true,
      shouldDirty: true,
      shouldTouch: true,
    });

    form.clearErrors();
  };

  const handleBack = () => {
    setCurrentStep(DialogStep.SELECT);
  };

  const config = {
    create: {
      title: "Create dashboard",
      description:
        "Use a template with common metrics and adjust it to your needs, or start with an empty dashboard.",
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
      const dashboardId = dashboardData?.id;
      if (dashboardId) {
        // Force synchronous state update before navigation
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
          if (values.templateType) {
            const template =
              DASHBOARD_TEMPLATES[values.templateType as TEMPLATE_TYPE];
            dashboardConfig = regenerateAllIds(template.config);
          } else {
            dashboardConfig = generateEmptyDashboard();
          }

          dashboardConfig.config.projectIds = values.projectId
            ? [values.projectId]
            : [];
          dashboardConfig.config.experimentIds = values.experimentIds || [];
          dashboardConfig.config.experimentDataSource =
            values.experimentDataSource ||
            EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP;
          dashboardConfig.config.experimentFilters =
            (values.experimentFilters as Filters) || [];
          dashboardConfig.config.maxExperimentsCount =
            values.maxExperimentsCount
              ? parseInt(values.maxExperimentsCount, 10)
              : DEFAULT_MAX_EXPERIMENTS;
        } else if (mode === "save_as" || mode === "clone") {
          dashboardConfig = regenerateAllIds(dashboard!.config);
          dashboardConfig.config.projectIds = values.projectId
            ? [values.projectId]
            : defaultProjectId
              ? [defaultProjectId]
              : [];
          dashboardConfig.config.experimentIds =
            values.experimentIds ||
            (defaultExperimentIds && defaultExperimentIds.length > 0
              ? defaultExperimentIds
              : []);
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
            onSuccess: (data) => {
              onDashboardCreated(data);
              setOpen(false);
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
      defaultProjectId,
      defaultExperimentIds,
      onDashboardCreated,
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
          {isCreateMode && currentStep === DialogStep.SELECT && (
            <DashboardDialogSelectStep onSelect={handleSelectOption} />
          )}

          {currentStep === DialogStep.DETAILS && (
            <Form {...form}>
              <form
                onSubmit={form.handleSubmit(onSubmit)}
                id="dashboard-form"
                className="flex flex-col gap-4"
              >
                <DashboardDialogDetailsStep
                  control={form.control}
                  templateType={
                    isCreateMode ? form.watch("templateType") : undefined
                  }
                  showDataSourceSection={isCreateMode}
                  descriptionExpanded={!isCreateMode}
                  onSubmit={form.handleSubmit(onSubmit)}
                />
              </form>
            </Form>
          )}
        </DialogAutoScrollBody>

        <DialogFooter className="flex flex-row justify-between gap-2 sm:flex-row sm:justify-between">
          <div>
            {isCreateMode && currentStep === DialogStep.DETAILS && (
              <Button variant="outline" onClick={handleBack}>
                <ChevronLeft className="mr-2 size-4" />
                Back
              </Button>
            )}
          </div>

          <div className="flex gap-2">
            {currentStep === DialogStep.DETAILS && (
              <DialogClose asChild>
                <Button variant="outline" disabled={isPending}>
                  Cancel
                </Button>
              </DialogClose>
            )}

            {currentStep === DialogStep.DETAILS && (
              <Button type="submit" form="dashboard-form" disabled={isPending}>
                {isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
                {config.buttonText}
              </Button>
            )}
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditCloneDashboardDialog;
