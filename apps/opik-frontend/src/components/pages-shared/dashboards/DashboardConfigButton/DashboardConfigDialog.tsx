import React, { useCallback, useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
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
import { DialogAutoScrollBody } from "@/components/ui/dialog";
import { Form } from "@/components/ui/form";
import {
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
  DEFAULT_MAX_EXPERIMENTS,
  isValidIntegerInRange,
} from "@/lib/dashboard/utils";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import {
  useDashboardStore,
  selectConfig,
  selectSetConfig,
} from "@/store/DashboardStore";
import { EXPERIMENT_DATA_SOURCE, BaseDashboardConfig } from "@/types/dashboard";
import { Filters } from "@/types/filters";
import DashboardDataSourceSection from "@/components/pages-shared/dashboards/DashboardDataSourceSection/DashboardDataSourceSection";

const DashboardConfigFormSchema = z
  .object({
    projectId: z.string().optional(),
    experimentIds: z.array(z.string()).optional(),
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

type DashboardConfigFormData = z.infer<typeof DashboardConfigFormSchema>;

interface DashboardConfigDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  disableProjectSelector?: boolean;
  disableExperimentsSelector?: boolean;
}

const getFormDefaults = (
  config?: BaseDashboardConfig | null,
): DashboardConfigFormData => ({
  projectId: config?.projectIds?.[0] || "",
  experimentIds: config?.experimentIds.slice() || [],
  experimentDataSource:
    config?.experimentDataSource || EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
  experimentFilters: config?.experimentFilters?.slice() ?? [],
  maxExperimentsCount:
    config?.maxExperimentsCount?.toString() || String(DEFAULT_MAX_EXPERIMENTS),
});

const DashboardConfigDialog: React.FC<DashboardConfigDialogProps> = ({
  open,
  onOpenChange,
  disableProjectSelector = false,
  disableExperimentsSelector = false,
}) => {
  const config = useDashboardStore(selectConfig);
  const setConfig = useDashboardStore(selectSetConfig);

  const form = useForm<DashboardConfigFormData>({
    resolver: zodResolver(DashboardConfigFormSchema),
    mode: "onChange",
    defaultValues: getFormDefaults(config),
  });

  useEffect(() => {
    if (open && config) {
      form.reset(getFormDefaults(config));
    }
  }, [open, config, form]);

  const handleSubmit = useCallback(
    (values: DashboardConfigFormData) => {
      if (!config) return;

      const maxExperimentsCount = values.maxExperimentsCount
        ? parseInt(values.maxExperimentsCount, 10)
        : DEFAULT_MAX_EXPERIMENTS;

      setConfig({
        ...config,
        projectIds: values.projectId ? [values.projectId] : [],
        experimentIds: values.experimentIds || [],
        experimentDataSource:
          values.experimentDataSource ||
          EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
        experimentFilters: (values.experimentFilters as Filters) || [],
        maxExperimentsCount,
      });

      onOpenChange(false);
    },
    [config, setConfig, onOpenChange],
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Dashboard defaults</DialogTitle>
          <DialogDescription>
            Select the defaults to visualize data for this dashboard. Individual
            widgets can override these settings if needed.
          </DialogDescription>
        </DialogHeader>

        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              onSubmit={form.handleSubmit(handleSubmit)}
              id="dashboard-config-form"
              className="flex flex-col gap-4"
            >
              <DashboardDataSourceSection
                disableProjectSelector={disableProjectSelector}
                disableExperimentsSelector={disableExperimentsSelector}
              />
            </form>
          </Form>
        </DialogAutoScrollBody>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" form="dashboard-config-form">
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DashboardConfigDialog;
