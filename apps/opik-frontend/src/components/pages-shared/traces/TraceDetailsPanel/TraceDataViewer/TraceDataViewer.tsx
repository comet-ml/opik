import React, { useCallback } from "react";
import get from "lodash/get";
import capitalize from "lodash/capitalize";
import isNumber from "lodash/isNumber";
import copy from "clipboard-copy";
import { StringParam, useQueryParam } from "use-query-params";
import { Clock, Coins, Copy, Hash, PenLine } from "lucide-react";

import { Span, Trace } from "@/types/traces";
import {
  METADATA_AGENT_GRAPH_KEY,
  TRACE_TYPE_FOR_TREE,
} from "@/constants/traces";
import BaseTraceDataTypeIcon from "../BaseTraceDataTypeIcon";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import TagList from "../TagList/TagList";
import InputOutputTab from "./InputOutputTab";
import MetadataTab from "./MatadataTab";
import FeedbackScoreTab from "./FeedbackScoreTab";
import AgentGraphTab from "./AgentGraphTab";
import ErrorTab from "./ErrorTab";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { formatDuration } from "@/lib/date";
import { isObjectSpan } from "@/lib/traces";
import isUndefined from "lodash/isUndefined";
import { formatCost } from "@/lib/money";

type TraceDataViewerProps = {
  data: Trace | Span;
  trace?: Trace;
  projectId: string;
  traceId: string;
  spanId?: string;
  annotateOpen: boolean;
  setAnnotateOpen: (open: boolean) => void;
};

const TraceDataViewer: React.FunctionComponent<TraceDataViewerProps> = ({
  data,
  trace,
  projectId,
  traceId,
  spanId,
  annotateOpen,
  setAnnotateOpen,
}) => {
  const { toast } = useToast();
  const isSpan = isObjectSpan(data);
  const type = get(data, "type", TRACE_TYPE_FOR_TREE);
  const entity = isSpan ? "span" : "trace";
  const tokens = data.usage?.total_tokens;

  const agentGraphData = get(
    trace,
    ["metadata", METADATA_AGENT_GRAPH_KEY],
    null,
  );
  const hasAgentGraph = Boolean(agentGraphData);
  const hasError = Boolean(data.error_info);

  const [tab = "input", setTab] = useQueryParam("traceTab", StringParam, {
    updateType: "replaceIn",
  });

  const selectedTab =
    (tab === "graph" && !hasAgentGraph) || (tab === "error" && !hasError)
      ? "input"
      : tab;

  const copyClickHandler = useCallback(() => {
    toast({
      description: `${capitalize(entity)} ID successfully copied to clipboard`,
    });
    copy(data.id);
  }, [toast, entity, data.id]);

  return (
    <div className="size-full max-w-full overflow-auto p-6">
      <div className="min-w-[400px] max-w-full overflow-x-hidden">
        <div className="mb-6 flex flex-col gap-1">
          <div className="flex w-full items-center justify-between gap-2 overflow-x-hidden">
            <div className="flex items-center gap-2 overflow-x-hidden">
              <BaseTraceDataTypeIcon type={type} />
              <div
                data-testid="data-viewer-title"
                className="comet-title-m truncate"
              >
                {data?.name}
              </div>
            </div>
            <div className="flex flex-nowrap gap-2">
              <Button size="sm" variant="ghost" onClick={copyClickHandler}>
                <Copy className="mr-2 size-4" />
                Copy ID
              </Button>
              {!annotateOpen && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setAnnotateOpen(true)}
                >
                  <PenLine className="mr-2 size-4" />
                  Annotate
                </Button>
              )}
            </div>
          </div>
          <div className="comet-body-s-accented flex w-full items-center gap-3 overflow-x-hidden text-muted-slate">
            <TooltipWrapper content="Duration in seconds">
              <div
                data-testid="data-viewer-duration"
                className="flex items-center gap-2 px-1"
              >
                <Clock className="size-4 shrink-0" />
                {formatDuration(data.duration)}
              </div>
            </TooltipWrapper>
            {isNumber(tokens) && (
              <TooltipWrapper content="Total amount of tokens">
                <div
                  data-testid="data-viewer-tokens"
                  className="flex items-center gap-2 px-1"
                >
                  <Hash className="size-4 shrink-0" />
                  {tokens} tokens
                </div>
              </TooltipWrapper>
            )}
            {Boolean(data.feedback_scores?.length) && (
              <TooltipWrapper content="Number of feedback scores">
                <div
                  data-testid="data-viewer-scores"
                  className="flex items-center gap-2 px-1"
                >
                  <PenLine className="size-4 shrink-0" />
                  {data.feedback_scores?.length} feedback scores
                </div>
              </TooltipWrapper>
            )}
            {!isUndefined(data.total_estimated_cost) && (
              <TooltipWrapper content="Estimated cost">
                <div
                  data-testid="data-viewer-scores"
                  className="flex items-center gap-2 break-all px-1"
                >
                  <Coins className="size-4 shrink-0" />
                  {formatCost(data.total_estimated_cost)}
                </div>
              </TooltipWrapper>
            )}
          </div>
          <TagList
            data={data}
            tags={data.tags}
            projectId={projectId}
            traceId={traceId}
            spanId={spanId}
          />
        </div>

        <Tabs defaultValue="input" value={selectedTab!} onValueChange={setTab}>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="input">
              Input/Output
            </TabsTrigger>
            <TabsTrigger variant="underline" value="feedback_scores">
              Feedback scores
            </TabsTrigger>
            <TabsTrigger variant="underline" value="metadata">
              Metadata
            </TabsTrigger>
            {hasAgentGraph && (
              <TabsTrigger variant="underline" value="graph">
                Agent graph
              </TabsTrigger>
            )}
            {hasError && (
              <TabsTrigger variant="underline" value="error">
                Error
              </TabsTrigger>
            )}
          </TabsList>
          <TabsContent value="input">
            <InputOutputTab data={data} />
          </TabsContent>
          <TabsContent value="feedback_scores">
            <FeedbackScoreTab data={data} traceId={traceId} spanId={spanId} />
          </TabsContent>
          <TabsContent value="metadata">
            <MetadataTab data={data} />
          </TabsContent>
          {hasAgentGraph && (
            <TabsContent value="graph">
              <AgentGraphTab data={agentGraphData} />
            </TabsContent>
          )}
          {hasError && (
            <TabsContent value="error">
              <ErrorTab data={data} />
            </TabsContent>
          )}
        </Tabs>
      </div>
    </div>
  );
};

export default TraceDataViewer;
