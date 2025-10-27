import React, { useCallback, useRef, useState } from "react";
import { MoreHorizontal, Pencil, Trash2, Copy } from "lucide-react";
import { DashboardChart } from "@/types/dashboards";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ChartDisplay from "./ChartDisplay";
import useChartDeleteMutation from "@/api/dashboards/useChartDeleteMutation";

interface ChartCardProps {
  chart: DashboardChart;
  dashboardId: string;
  onEditChart: (chartId: string) => void;
}

const ChartCard: React.FC<ChartCardProps> = ({
  chart,
  dashboardId,
  onEditChart,
}) => {
  const resetKeyRef = useRef(0);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const deleteChartMutation = useChartDeleteMutation();

  const handleDelete = useCallback(() => {
    if (!chart.id) return;
    deleteChartMutation.mutate({
      dashboardId,
      chartId: chart.id,
    });
  }, [chart.id, dashboardId, deleteChartMutation]);

  return (
    <>
      <Card className="h-[400px] flex flex-col">
        <CardHeader className="flex-shrink-0 pb-3">
          <div className="flex items-start justify-between">
            <div className="flex-1 min-w-0">
              <CardTitle className="text-base font-medium truncate">
                {chart.name}
              </CardTitle>
              {chart.description && (
                <p className="text-sm text-light-slate mt-1 line-clamp-2">
                  {chart.description}
                </p>
              )}
            </div>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm" className="h-8 w-8 p-0">
                  <MoreHorizontal className="h-4 w-4" />
                  <span className="sr-only">Open menu</span>
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  onClick={() => chart.id && onEditChart(chart.id)}
                >
                  <Pencil className="mr-2 h-4 w-4" />
                  Edit
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={() => {
                    // TODO: Implement clone functionality
                    console.log("Clone chart:", chart.id);
                  }}
                >
                  <Copy className="mr-2 h-4 w-4" />
                  Clone
                </DropdownMenuItem>
                <DropdownMenuItem
                  className="text-red-600 focus:text-red-600"
                  onClick={() => {
                    setShowDeleteDialog(true);
                    resetKeyRef.current = resetKeyRef.current + 1;
                  }}
                >
                  <Trash2 className="mr-2 h-4 w-4" />
                  Delete
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </CardHeader>
        <CardContent className="flex-1 overflow-hidden p-4 pt-0">
          <ChartDisplay
            chart={chart}
            dashboardId={dashboardId}
          />
        </CardContent>
      </Card>

      {/* Delete Confirmation Dialog */}
      <ConfirmDialog
        key={`delete-chart-${resetKeyRef.current}`}
        open={showDeleteDialog}
        setOpen={setShowDeleteDialog}
        onConfirm={handleDelete}
        title="Delete chart"
        description={`Are you sure you want to delete "${chart.name}"? This action cannot be undone.`}
        confirmText="Delete chart"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default ChartCard;
