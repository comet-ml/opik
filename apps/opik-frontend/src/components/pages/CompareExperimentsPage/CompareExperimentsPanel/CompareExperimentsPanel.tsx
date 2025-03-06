import React, { useCallback, useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import findIndex from "lodash/findIndex";
import sortBy from "lodash/sortBy";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";

import NoData from "@/components/shared/NoData/NoData";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import ShareURLButton from "@/components/shared/ShareURLButton/ShareURLButton";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { ExperimentsCompare } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
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

  const experimentItems = useMemo(() => {
    return sortBy(experimentsCompare?.experiment_items || [], (e) =>
      findIndex(experimentsIds, (id) => e.id === id),
    );
  }, [experimentsCompare?.experiment_items, experimentsIds]);

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

  const data = datasetItem?.data || experimentsCompare?.data;

  const copyClickHandler = useCallback(() => {
    if (experimentsCompare?.id) {
      toast({
        description: "ID successfully copied to clipboard",
      });
      copy(experimentsCompare?.id);
    }
  }, [toast, experimentsCompare?.id]);

  const renderContent = () => {
    if (!experimentsCompare) {
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
      <div className="flex gap-2">
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
      hasPreviousRow={hasPreviousRow}
      hasNextRow={hasNextRow}
      onClose={onClose}
      onRowChange={onRowChange}
      initialWidth={0.8}
      ignoreHotkeys={isTraceDetailsOpened}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default CompareExperimentsPanel;
