import React, { useCallback, useMemo } from "react";
import findIndex from "lodash/findIndex";
import sortBy from "lodash/sortBy";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";

import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import NoData from "@/components/shared/NoData/NoData";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import ShareURLButton from "@/components/shared/ShareURLButton/ShareURLButton";
import { ExperimentsCompare } from "@/types/datasets";
import CompareExperimentsViewer from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer";
import { OnChangeFn } from "@/types/shared";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";

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

  const copyClickHandler = useCallback(() => {
    if (experimentsCompare?.id) {
      toast({
        description: "ID successfully copied to clipboard",
      });
      copy(experimentsCompare?.id);
    }
  }, [toast, experimentsCompare?.id]);

  const renderExperimentsSection = () => {
    let className = "";
    switch (experimentItems.length) {
      case 1:
        className = "basis-full";
        break;
      case 2:
        className = "basis-1/2";
        break;
      default:
        className = "basis-1/3 max-w-[33.3333%]";
        break;
    }

    return (
      <div className="flex size-full overflow-auto">
        <div className="inline-flex h-max min-h-full min-w-full items-stretch">
          {experimentItems.map((experimentItem) => (
            <div
              key={experimentItem.id}
              className={cn(
                "flex border-r min-w-72 last:border-none",
                className,
              )}
            >
              <CompareExperimentsViewer
                experimentItem={experimentItem}
                openTrace={openTrace}
              />
            </div>
          ))}
        </div>
      </div>
    );
  };

  const renderContent = () => {
    if (!experimentsCompare) {
      return <NoData />;
    }

    return (
      <div className="relative size-full">
        <ResizablePanelGroup
          direction="vertical"
          autoSaveId="compare-vetical-sidebar"
        >
          <ResizablePanel defaultSize={50} minSize={20}>
            <div className="size-full overflow-auto p-6">
              <div className="min-w-72 max-w-full overflow-x-hidden">
                <h2 className="comet-title-m mb-4">Data</h2>
                {experimentsCompare.data ? (
                  <SyntaxHighlighter data={experimentsCompare.data} />
                ) : (
                  <NoData />
                )}
              </div>
            </div>
          </ResizablePanel>
          <ResizableHandle />
          <ResizablePanel defaultSize={50} minSize={20}>
            {renderExperimentsSection()}
          </ResizablePanel>
        </ResizablePanelGroup>
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
