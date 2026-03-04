import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";
import get from "lodash/get";
import isObject from "lodash/isObject";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import FeedbackScoreTagCell from "@/components/shared/DataTableCells/FeedbackScoreTagCell";
import OptimizationStatusCell from "@/components/pages/OptimizationsPage/OptimizationStatusCell";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import Loader from "@/components/shared/Loader/Loader";
import AddOptimizationDialog from "@/components/pages/OptimizationsPage/AddOptimizationDialog/AddOptimizationDialog";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { COLUMN_NAME_ID, COLUMN_SELECT_ID, COLUMN_TYPE } from "@/types/shared";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Optimization } from "@/types/optimizations";
import { convertColumnDataToColumn } from "@/lib/table";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { toString } from "@/lib/utils";
import { getFeedbackScore } from "@/lib/feedback-scores";
import { OPTIMIZATION_OPTIMIZER_KEY } from "@/constants/experiments";
import { getOptimizerLabel } from "@/lib/optimizations";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const COLUMNS_WIDTH_KEY = "home-optimizations-columns-width";

export const COLUMNS = convertColumnDataToColumn<Optimization, Optimization>(
  [
    {
      id: COLUMN_NAME_ID,
      label: "Optimization",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      sortable: true,
      customMeta: {
        nameKey: "name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.optimization,
        getSearch: (data: Optimization) => ({
          optimizations: [data.id],
        }),
      },
    },
    {
      id: "dataset",
      label: "Dataset",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      customMeta: {
        nameKey: "dataset_name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.dataset,
      },
    },
    {
      id: "optimizer",
      label: "Optimizer",
      type: COLUMN_TYPE.string,
      accessorFn: (row) => {
        const metadataVal = get(row.metadata ?? {}, OPTIMIZATION_OPTIMIZER_KEY);
        if (metadataVal) {
          return isObject(metadataVal)
            ? JSON.stringify(metadataVal, null, 2)
            : toString(metadataVal);
        }
        const studioVal = row.studio_config?.optimizer?.type;
        return studioVal ? getOptimizerLabel(studioVal) : "-";
      },
      explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimizer],
    },
    {
      id: "objective_name",
      label: "Best score",
      type: COLUMN_TYPE.numberDictionary,
      accessorFn: (row) =>
        getFeedbackScore(row.feedback_scores ?? [], row.objective_name),
      cell: FeedbackScoreTagCell as never,
      explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_best_score],
    },
    {
      id: "status",
      label: "Status",
      type: COLUMN_TYPE.string,
      cell: OptimizationStatusCell as never,
    },
    {
      id: "num_trials",
      label: "Trials",
      type: COLUMN_TYPE.number,
    },
    {
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      cell: TimeCell as never,
      sortable: true,
    },
  ],
  {},
);

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const OptimizationRunsSection: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const { data, isPending } = useOptimizationsList(
    {
      workspaceName,
      page: 1,
      size: 5,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const optimizations = useMemo(() => data?.content ?? [], [data?.content]);
  const noDataText = "There are no optimization runs yet";

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const handleRowClick = useCallback(
    (row: Optimization) => {
      navigate({
        to: "/$workspaceName/optimizations/$datasetId/compare",
        params: {
          datasetId: row.dataset_id,
          workspaceName,
        },
        search: {
          optimizations: [row.id],
        },
      });
    },
    [navigate, workspaceName],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewOptimizationClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pb-4 pt-2">
      <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
        Optimization runs
      </h2>
      <DataTable
        columns={COLUMNS}
        data={optimizations}
        onRowClick={handleRowClick}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            <Button variant="link" onClick={handleNewOptimizationClick}>
              Create new optimization
            </Button>
          </DataTableNoData>
        }
      />
      <div className="flex justify-end pt-1">
        <Link to="/$workspaceName/optimizations" params={{ workspaceName }}>
          <Button variant="ghost" className="flex items-center gap-1 pr-0">
            All optimization runs <ArrowRight className="size-4" />
          </Button>
        </Link>
      </div>
      <AddOptimizationDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default OptimizationRunsSection;
