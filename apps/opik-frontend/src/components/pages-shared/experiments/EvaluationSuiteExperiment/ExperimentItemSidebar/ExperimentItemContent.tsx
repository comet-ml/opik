import React, { useMemo } from "react";
import sortBy from "lodash/sortBy";
import { ListTree } from "lucide-react";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";

import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import NoData from "@/components/shared/NoData/NoData";
import { Button } from "@/components/ui/button";
import { DatasetItem, ExperimentItem } from "@/types/datasets";
import { OnChangeFn } from "@/types/shared";
import {
  BehaviorResult,
  ExperimentItemStatus,
} from "@/types/evaluation-suites";
import { traceExist, traceVisible } from "@/lib/traces";
import PassFailBadge from "./PassFailBadge";
import BehaviorsResultsTable from "./BehaviorsResultsTable";
import MultiRunTabs from "./MultiRunTabs";

type ExperimentItemContentProps = {
  data?: DatasetItem["data"];
  experimentItems: ExperimentItem[];
  openTrace: OnChangeFn<string>;
};

const ExperimentItemContent: React.FunctionComponent<
  ExperimentItemContentProps
> = ({ data, experimentItems, openTrace }) => {
  const sortedItems = useMemo(
    () => sortBy(experimentItems, "created_at"),
    [experimentItems],
  );

  const aggregateStatus = useMemo(() => {
    if (sortedItems.length === 0) return undefined;
    const itemRecord = sortedItems[0] as unknown as Record<string, unknown>;
    return itemRecord.status as ExperimentItemStatus | undefined;
  }, [sortedItems]);

  const renderRunContent = (item: ExperimentItem, idx: number) => {
    const isTraceExist = traceExist(item);
    const isTraceVisible = traceVisible(item);
    const itemRecord = item as unknown as Record<string, unknown>;
    const behaviors = (itemRecord.behavior_results ?? []) as BehaviorResult[];

    const onTraceClick = (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      if (item.trace_id) {
        openTrace(item.trace_id);
      }
    };

    return (
      <div className="flex flex-col gap-4">
        {!isTraceExist ? (
          <div className="mt-16">
            <NoData
              title="No related trace found"
              message="It looks like it was deleted or not created"
              className="min-h-24 text-center"
            />
          </div>
        ) : (
          <>
            <div>
              <div className="mb-2 flex items-center justify-between">
                <h4 className="comet-body-s-accented">Output</h4>
                {isTraceVisible && (
                  <TooltipWrapper content="Click to open original trace">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={onTraceClick}
                      className="shrink-0"
                    >
                      <ListTree className="mr-2 size-4 shrink-0" />
                      Trace
                    </Button>
                  </TooltipWrapper>
                )}
              </div>
              {item.output ? (
                <SyntaxHighlighter
                  data={item.output}
                  prettifyConfig={{ fieldType: "output" }}
                  preserveKey={`eval-suite-sidebar-output-${idx}`}
                />
              ) : null}
            </div>
            <BehaviorsResultsTable behaviors={behaviors} />
          </>
        )}
      </div>
    );
  };

  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="eval-suite-sidebar"
      style={{ height: "unset", overflow: "unset" }}
      className="min-h-full"
    >
      <ResizablePanel defaultSize={35} className="min-w-72">
        <div className="pr-6 pt-4">
          <h4 className="comet-body-accented pb-4">
            Evaluation suite item context
          </h4>
          {data ? (
            <SyntaxHighlighter
              data={data}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="eval-suite-sidebar-context"
            />
          ) : (
            <NoData title="No data" className="min-h-24" />
          )}
        </div>
      </ResizablePanel>
      <ResizableHandle />
      <ResizablePanel className="min-w-72" style={{ overflow: "unset" }}>
        <div className="px-6 pt-4">
          <div className="flex items-center gap-2 pb-4">
            <h4 className="comet-body-accented">Experiment results</h4>
            <PassFailBadge status={aggregateStatus} />
          </div>
          <MultiRunTabs
            experimentItems={sortedItems}
            renderRunContent={renderRunContent}
          />
        </div>
      </ResizablePanel>
    </ResizablePanelGroup>
  );
};

export default ExperimentItemContent;
