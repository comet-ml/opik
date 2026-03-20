import React, { useCallback, useEffect, useMemo, useState } from "react";
import sortBy from "lodash/sortBy";
import groupBy from "lodash/groupBy";
import isFunction from "lodash/isFunction";
import { Database, ListTree } from "lucide-react";
import { Link } from "@tanstack/react-router";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";

import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import NoData from "@/shared/NoData/NoData";
import { Tag } from "@/ui/tag";
import {
  DatasetItem,
  Experiment,
  ExperimentItem,
  ExperimentRunSummary,
} from "@/types/datasets";
import { ExperimentItemStatus } from "@/types/evaluation-suites";
import { OnChangeFn } from "@/types/shared";
import { traceExist, traceVisible } from "@/lib/traces";
import useAppStore from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import PassFailBadge from "./PassFailBadge";
import AssertionResultsTable from "./AssertionResultsTable";
import MultiRunTabs from "./MultiRunTabs";

type SingleExperimentSectionProps = {
  experimentItems: ExperimentItem[];
  experimentName: string;
  status: ExperimentItemStatus | undefined;
  openTrace: OnChangeFn<string>;
  sectionIdx: number;
};

const SingleExperimentSection: React.FC<SingleExperimentSectionProps> = ({
  experimentItems,
  experimentName,
  status,
  openTrace,
  sectionIdx,
}) => {
  const [activeRunIndex, setActiveRunIndex] = useState(0);

  const sortedItems = useMemo(
    () => sortBy(experimentItems, "created_at"),
    [experimentItems],
  );

  useEffect(() => setActiveRunIndex(0), [experimentItems]);

  const resolvedStatus =
    status ?? (sortedItems.length > 0 ? sortedItems[0].status : undefined);

  const activeItem = sortedItems[activeRunIndex];
  const showGoToTraces =
    activeItem && traceExist(activeItem) && traceVisible(activeItem);

  const [outputMaxHeight, setOutputMaxHeight] = useState("700px");
  const handleOutputResize = useCallback((node: HTMLDivElement) => {
    // 40px = SyntaxHighlighterLayout header (h-10)
    const available = node.clientHeight - 40;
    setOutputMaxHeight(`${Math.max(available, 100)}px`);
  }, []);
  const { ref: outputRef } =
    useObserveResizeNode<HTMLDivElement>(handleOutputResize);

  const renderRunContent = (item: ExperimentItem, idx: number) => {
    const assertions = item.assertion_results ?? [];

    if (!traceExist(item)) {
      return (
        <div className="mt-16 flex-1">
          <NoData
            title="No related trace found"
            message="It looks like it was deleted or not created"
            className="min-h-24 text-center"
          />
        </div>
      );
    }

    return (
      <>
        <div ref={outputRef} className="min-h-0 flex-1 overflow-hidden">
          {item.output && (
            <SyntaxHighlighter
              data={item.output}
              preserveKey={`eval-suite-sidebar-output-${sectionIdx}-${idx}`}
              maxHeight={outputMaxHeight}
            />
          )}
        </div>
        {assertions.length > 0 && (
          <div className="min-h-0 flex-1 overflow-hidden border-t border-border py-4">
            <AssertionResultsTable assertions={assertions} />
          </div>
        )}
      </>
    );
  };

  return (
    <div className="flex h-full flex-col px-6 pt-4">
      <div className="flex shrink-0 items-center justify-between pb-4">
        <div className="flex items-center gap-2">
          <h4 className="comet-body-accented truncate">{experimentName}</h4>
          <PassFailBadge status={resolvedStatus} />
        </div>
        {showGoToTraces && (
          <Tag
            variant="default"
            size="md"
            className="flex shrink-0 cursor-pointer items-center gap-1.5"
            onClick={(e) => {
              e.stopPropagation();
              if (isFunction(openTrace) && activeItem.trace_id) {
                openTrace(activeItem.trace_id);
              }
            }}
          >
            <ListTree className="size-3" />
            Trace
          </Tag>
        )}
      </div>
      <MultiRunTabs
        experimentItems={sortedItems}
        renderRunContent={renderRunContent}
        activeIndex={activeRunIndex}
        onActiveIndexChange={setActiveRunIndex}
      />
    </div>
  );
};

type ExperimentItemContentProps = {
  data?: DatasetItem["data"];
  experimentItems: ExperimentItem[];
  openTrace: OnChangeFn<string>;
  description?: string;
  experiments?: Experiment[];
  datasetId?: string;
  datasetItemId?: string;
  experimentsIds: string[];
  runSummariesByExperiment?: Record<string, ExperimentRunSummary>;
};

export const ExperimentItemContent: React.FC<ExperimentItemContentProps> = ({
  data,
  experimentItems,
  openTrace,
  description,
  experiments,
  datasetId,
  datasetItemId,
  experimentsIds,
  runSummariesByExperiment,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const itemsByExperiment = useMemo(
    () => groupBy(experimentItems, "experiment_id"),
    [experimentItems],
  );

  const experimentNameMap = useMemo(
    () =>
      Object.fromEntries((experiments ?? []).map((exp) => [exp.id, exp.name])),
    [experiments],
  );

  const renderItemContextPanel = () => (
    <ResizablePanel defaultSize={35} className="min-w-72">
      <div className="h-full overflow-auto pr-6 pt-4">
        <div className="flex items-center justify-between pb-4">
          <h4 className="comet-body-accented">Item context</h4>
          {datasetId && (
            <Link
              to="/$workspaceName/evaluation-suites/$suiteId/items"
              params={{ workspaceName, suiteId: datasetId }}
              search={datasetItemId ? { row: datasetItemId } : {}}
              onClick={(e) => e.stopPropagation()}
            >
              <Tag
                variant="default"
                size="md"
                className="flex cursor-pointer items-center gap-1.5"
              >
                <Database className="size-3" />
                View evaluation item
              </Tag>
            </Link>
          )}
        </div>
        {description && (
          <div className="pb-4">
            <h4 className="comet-body-s-accented px-0.5 pb-0.5">Description</h4>
            <div className="rounded-md border border-border px-3 py-2">
              <p className="comet-body-s truncate">{description}</p>
            </div>
          </div>
        )}
        <div>
          <h4 className="comet-body-s-accented px-0.5 pb-0.5">Data</h4>
          {data ? (
            <SyntaxHighlighter
              data={data}
              preserveKey="eval-suite-sidebar-context"
            />
          ) : (
            <NoData title="No data" className="min-h-24" />
          )}
        </div>
      </div>
    </ResizablePanel>
  );

  const autoSaveId =
    experimentsIds.length > 1
      ? "eval-suite-sidebar-compare"
      : "eval-suite-sidebar";

  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId={autoSaveId}
      className="h-full"
    >
      {renderItemContextPanel()}
      {experimentsIds.map((expId, idx) => {
        const items = itemsByExperiment[expId] ?? [];
        const defaultName =
          experimentsIds.length > 1
            ? `Experiment ${idx + 1}`
            : "Experiment results";
        const name = experimentNameMap[expId] ?? defaultName;
        const status = runSummariesByExperiment?.[expId]?.status;

        return (
          <React.Fragment key={expId}>
            <ResizableHandle />
            <ResizablePanel className="min-w-72">
              <SingleExperimentSection
                experimentItems={items}
                experimentName={name}
                status={status}
                openTrace={openTrace}
                sectionIdx={idx}
              />
            </ResizablePanel>
          </React.Fragment>
        );
      })}
    </ResizablePanelGroup>
  );
};

export default ExperimentItemContent;
