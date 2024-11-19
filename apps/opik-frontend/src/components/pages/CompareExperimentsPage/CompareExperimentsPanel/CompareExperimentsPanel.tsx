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
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import CompareExperimentsViewer from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsViewer";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { extractImageUrls } from "@/lib/images";
import { cn } from "@/lib/utils";
import { ExperimentsCompare } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";

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

  const imagesUrls = useMemo(
    () => extractImageUrls(experimentsCompare?.data),
    [experimentsCompare?.data],
  );
  const hasImages = imagesUrls.length > 0;

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
                <Accordion
                  type="multiple"
                  className="w-full"
                  defaultValue={["images", "data"]}
                >
                  {hasImages ? (
                    <AccordionItem value="images">
                      <AccordionTrigger>Images</AccordionTrigger>
                      <AccordionContent>
                        <div className="flex flex-wrap gap-2">
                          {imagesUrls.map((imageUrl, index) => {
                            return (
                              <div
                                key={index + imageUrl.substring(0, 10)}
                                className="h-[200px] max-w-[300px] rounded-md border p-4"
                              >
                                <img
                                  src={imageUrl}
                                  loading="lazy"
                                  alt={`image-${index}`}
                                  className="size-full object-contain"
                                />
                              </div>
                            );
                          })}
                        </div>
                      </AccordionContent>
                    </AccordionItem>
                  ) : null}

                  <AccordionItem value="data">
                    <AccordionTrigger>Data</AccordionTrigger>
                    <AccordionContent>
                      {experimentsCompare.data ? (
                        <SyntaxHighlighter data={experimentsCompare.data} />
                      ) : (
                        <NoData />
                      )}
                    </AccordionContent>
                  </AccordionItem>
                </Accordion>
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
