import React, { useCallback, useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import sortBy from "lodash/sortBy";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";

import NoData from "@/components/shared/NoData/NoData";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import ShareURLButton from "@/components/shared/ShareURLButton/ShareURLButton";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { OnChangeFn, COLUMN_TYPE } from "@/types/shared";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import ExperimentItemContent from "./ExperimentItemContent";

type EvaluationSuiteExperimentPanelProps = {
  experimentsCompareId?: string | null;
  experimentsCompare?: ExperimentsCompare;
  experimentsIds: string[];
  experiments?: Experiment[];
  datasetId: string;
  openTrace: OnChangeFn<string>;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
  isTraceDetailsOpened: boolean;
};

export const EvaluationSuiteExperimentPanel: React.FC<
  EvaluationSuiteExperimentPanelProps
> = ({
  experimentsCompareId,
  experimentsCompare,
  experimentsIds,
  experiments,
  datasetId,
  openTrace,
  hasPreviousRow,
  hasNextRow,
  onClose,
  onRowChange,
  isTraceDetailsOpened,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: nonTruncatedData } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds,
      filters: [
        {
          id: "",
          field: "id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: experimentsCompareId as string,
        },
      ],
      truncate: false,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(experimentsCompareId) && Boolean(datasetId),
    },
  );

  const activeExperimentsCompare =
    nonTruncatedData?.content?.[0] ?? experimentsCompare;

  const experimentItems = useMemo(
    () =>
      sortBy(activeExperimentsCompare?.experiment_items || [], (e) =>
        experimentsIds.indexOf(e.experiment_id),
      ),
    [activeExperimentsCompare?.experiment_items, experimentsIds],
  );

  const datasetItemId = activeExperimentsCompare?.id;
  const { data: datasetItem } = useDatasetItemById(
    {
      datasetItemId: datasetItemId!,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(datasetItemId),
    },
  );

  const data = datasetItem?.data || activeExperimentsCompare?.data;
  const description = datasetItem?.description;

  const copyClickHandler = useCallback(() => {
    if (activeExperimentsCompare?.id) {
      toast({
        description: "ID successfully copied to clipboard",
      });
      copy(activeExperimentsCompare?.id);
    }
  }, [toast, activeExperimentsCompare?.id]);

  const horizontalNavigation = useMemo(
    () =>
      hasNextRow != null && hasPreviousRow != null && onRowChange
        ? {
            onChange: onRowChange,
            hasNext: hasNextRow,
            hasPrevious: hasPreviousRow,
          }
        : undefined,
    [hasNextRow, hasPreviousRow, onRowChange],
  );

  const renderContent = () => {
    if (!activeExperimentsCompare) {
      return <NoData />;
    }

    return (
      <div
        className="relative size-full pl-6"
        style={
          {
            "--experiment-sidebar-tab-content-height": "calc(100vh - 60px)",
          } as React.CSSProperties
        }
      >
        <div className="h-[var(--experiment-sidebar-tab-content-height)]">
          <ExperimentItemContent
            data={data}
            experimentItems={experimentItems}
            openTrace={openTrace}
            description={description}
            experiments={experiments}
            datasetId={datasetId}
            datasetItemId={datasetItemId}
            experimentsIds={experimentsIds}
            runSummariesByExperiment={
              activeExperimentsCompare?.run_summaries_by_experiment
            }
          />
        </div>
      </div>
    );
  };

  const headerContent = (
    <div className="flex flex-auto justify-end gap-2 pl-6">
      <ShareURLButton />
      <Button size="sm" variant="outline" onClick={copyClickHandler}>
        <Copy className="mr-2 size-4" />
        Copy ID
      </Button>
    </div>
  );

  return (
    <ResizableSidePanel
      panelId="eval-suite-experiment"
      entity="item"
      open={Boolean(experimentsCompareId)}
      headerContent={headerContent}
      onClose={onClose}
      initialWidth={0.8}
      ignoreHotkeys={isTraceDetailsOpened}
      horizontalNavigation={horizontalNavigation}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default EvaluationSuiteExperimentPanel;
