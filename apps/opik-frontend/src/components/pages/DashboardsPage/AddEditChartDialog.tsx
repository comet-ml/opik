import React, { useCallback, useEffect } from "react";
import { useForm } from "react-hook-form";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ChartType, DashboardChart } from "@/types/dashboards";
import useChartCreateMutation from "@/api/dashboards/useChartCreateMutation";
import useChartUpdateMutation from "@/api/dashboards/useChartUpdateMutation";
import useChartById from "@/api/dashboards/useChartById";
import Loader from "@/components/shared/Loader/Loader";

interface AddEditChartDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  dashboardId: string;
  chartId?: string;
}

interface ChartFormData {
  name: string;
  description?: string;
  chart_type: ChartType;
}

const AddEditChartDialog: React.FC<AddEditChartDialogProps> = ({
  open,
  setOpen,
  dashboardId,
  chartId,
}) => {
  const isEditMode = Boolean(chartId);

  const { data: existingChart, isPending: isLoadingChart } = useChartById(
    { dashboardId, chartId: chartId || "" },
    { enabled: isEditMode && Boolean(chartId) }
  );

  const createChartMutation = useChartCreateMutation();
  const updateChartMutation = useChartUpdateMutation();

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ChartFormData>({
    defaultValues: {
      name: "",
      description: "",
      chart_type: "line",
    },
  });

  const chartType = watch("chart_type");

  // Load existing chart data when editing
  useEffect(() => {
    if (existingChart) {
      reset({
        name: existingChart.name,
        description: existingChart.description || "",
        chart_type: existingChart.chart_type,
      });
    }
  }, [existingChart, reset]);

  // Reset form when dialog closes
  useEffect(() => {
    if (!open) {
      reset({
        name: "",
        description: "",
        chart_type: "line",
      });
    }
  }, [open, reset]);

  const onSubmit = useCallback(
    async (data: ChartFormData) => {
      const chartData: Partial<DashboardChart> = {
        name: data.name,
        description: data.description || undefined,
        chart_type: data.chart_type,
        // Default empty configuration
        data_series: [],
        position: undefined,
        group_by: undefined,
      };

      if (isEditMode && chartId) {
        await updateChartMutation.mutateAsync({
          dashboardId,
          chartId,
          chart: chartData,
        });
      } else {
        await createChartMutation.mutateAsync({
          dashboardId,
          chart: chartData,
        });
      }

      setOpen(false);
    },
    [
      isEditMode,
      chartId,
      dashboardId,
      createChartMutation,
      updateChartMutation,
      setOpen,
    ]
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            {isEditMode ? "Edit Chart" : "Add Chart"}
          </DialogTitle>
        </DialogHeader>

        {isEditMode && isLoadingChart ? (
          <div className="flex justify-center py-8">
            <Loader />
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            {/* Chart Name */}
            <div className="space-y-2">
              <Label htmlFor="name">
                Chart Name <span className="text-red-500">*</span>
              </Label>
              <Input
                id="name"
                placeholder="e.g., Trace Count Over Time"
                {...register("name", {
                  required: "Chart name is required",
                  maxLength: {
                    value: 255,
                    message: "Chart name must be less than 255 characters",
                  },
                })}
              />
              {errors.name && (
                <p className="text-sm text-red-500">{errors.name.message}</p>
              )}
            </div>

            {/* Chart Description */}
            <div className="space-y-2">
              <Label htmlFor="description">Description</Label>
              <Textarea
                id="description"
                placeholder="Optional description for this chart"
                rows={3}
                {...register("description")}
              />
            </div>

            {/* Chart Type */}
            <div className="space-y-2">
              <Label htmlFor="chart_type">
                Chart Type <span className="text-red-500">*</span>
              </Label>
              <Select
                value={chartType}
                onValueChange={(value) =>
                  setValue("chart_type", value as ChartType)
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="Select chart type" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="line">Line Chart</SelectItem>
                  <SelectItem value="bar">Bar Chart</SelectItem>
                </SelectContent>
              </Select>
              {errors.chart_type && (
                <p className="text-sm text-red-500">
                  {errors.chart_type.message}
                </p>
              )}
            </div>

            {/* Info Message */}
            <div className="rounded-md bg-blue-50 p-4 text-sm text-blue-800">
              <p>
                <strong>Note:</strong> After creating the chart, you can
                configure data series, filters, and grouping options by editing
                it.
              </p>
            </div>

            {/* Form Actions */}
            <div className="flex justify-end gap-2 pt-4">
              <Button
                type="button"
                variant="outline"
                onClick={() => setOpen(false)}
                disabled={isSubmitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting
                  ? "Saving..."
                  : isEditMode
                    ? "Update Chart"
                    : "Create Chart"}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
};

export default AddEditChartDialog;
