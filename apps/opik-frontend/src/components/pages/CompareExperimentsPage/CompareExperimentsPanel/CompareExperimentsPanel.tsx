import React, { useCallback, useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import findIndex from "lodash/findIndex";
import sortBy from "lodash/sortBy";
import copy from "clipboard-copy";
import isBoolean from "lodash/isBoolean";
import isFunction from "lodash/isFunction";
import { Copy } from "lucide-react";

import NoData from "@/components/shared/NoData/NoData";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import ShareURLButton from "@/components/shared/ShareURLButton/ShareURLButton";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { ExperimentsCompare } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import { COLUMN_TYPE } from "@/types/shared";
import DataTab from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/DataTab";

type CompareExperimentsPanelProps = {
  experimentsCompareId?: string | null;
  experimentsCompare?: ExperimentsCompare;
  experimentsIds: string[];
  openTrace: OnChangeFn<string>;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
  isTraceDetailsOpened: boolean;
};

const CompareExperimentsPanel: React.FunctionComponent<
  CompareExperimentsPanelProps
> = ({
  experimentsCompareId,
  experimentsCompare,
  experimentsIds,
  openTrace,
  hasPreviousRow,
  hasNextRow,
  onClose,
  onRowChange,
  isTraceDetailsOpened,
}) => {
  const { toast } = useToast();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const datasetId = useDatasetIdFromCompareExperimentsURL();

  // Fetch non-truncated data for the sidebar
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

  // Use non-truncated data when available, fall back to truncated data while loading
  const nonTruncatedExperimentsCompare = nonTruncatedData?.content?.[0];
  const activeExperimentsCompare =
    nonTruncatedExperimentsCompare ?? experimentsCompare;

  const experimentItems = useMemo(() => {
    return sortBy(activeExperimentsCompare?.experiment_items || [], (e) =>
      findIndex(experimentsIds, (id) => e.id === id),
    );
  }, [activeExperimentsCompare?.experiment_items, experimentsIds]);

  const datasetItemId = experimentItems?.[0]?.dataset_item_id || undefined;
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
      isBoolean(hasNextRow) &&
      isBoolean(hasPreviousRow) &&
      isFunction(onRowChange)
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
        <div className="mt-0 h-[var(--experiment-sidebar-tab-content-height)] overflow-auto">
          <DataTab
            data={data}
            experimentItems={experimentItems}
            openTrace={openTrace}
          />
        </div>
      </div>
    );
  };

  const renderHeaderContent = () => {
    return (
      <div className="flex flex-auto justify-end gap-2 pl-6">
        <ShareURLButton />
        <Button size="sm" variant="outline" onClick={copyClickHandler}>
          <Copy className="mr-2 size-4" />
          Copy ID
        </Button>
      </div>
    );
  };

  return (
    <ResizableSidePanel
      panelId="compare-experiments"
      entity="item"
      open={Boolean(experimentsCompareId)}
      headerContent={renderHeaderContent()}
      onClose={onClose}
      initialWidth={0.8}
      ignoreHotkeys={isTraceDetailsOpened}
      horizontalNavigation={horizontalNavigation}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default CompareExperimentsPanel;
