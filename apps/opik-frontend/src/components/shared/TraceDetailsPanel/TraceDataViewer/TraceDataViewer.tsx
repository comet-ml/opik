import React, { useCallback } from "react";
import get from "lodash/get";
import capitalize from "lodash/capitalize";
import isNumber from "lodash/isNumber";
import copy from "clipboard-copy";
import { StringParam, useQueryParam } from "use-query-params";
import { Clock, Copy, Hash, PenLine } from "lucide-react";

import { Span, Trace } from "@/types/traces";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import BaseTraceDataTypeIcon from "../BaseTraceDataTypeIcon";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import TagList from "../TagList/TagList";
import InputOutputTab from "./InputOutputTab";
import MetadataTab from "./MatadataTab";
import FeedbackScoreTab from "./FeedbackScoreTab";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { calcDuration, millisecondsToSeconds } from "@/lib/utils";
import { isObjectSpan } from "@/lib/traces";

type TraceDataViewerProps = {
  data: Trace | Span;
  projectId: string;
  traceId: string;
  spanId?: string;
  annotateOpen: boolean;
  setAnnotateOpen: (open: boolean) => void;
};

const TraceDataViewer: React.FunctionComponent<TraceDataViewerProps> = ({
  data,
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
  const duration = calcDuration(data.start_time, data.end_time);
  const tokens = data.usage?.total_tokens;

  const [tab = "input", setTab] = useQueryParam("traceTab", StringParam, {
    updateType: "replaceIn",
  });

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
                {isNaN(duration)
                  ? "NA"
                  : `${millisecondsToSeconds(duration)} seconds`}
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
          </div>
          <TagList
            data={data}
            tags={data.tags}
            projectId={projectId}
            traceId={traceId}
            spanId={spanId}
          />
        </div>

        <Tabs defaultValue="input" value={tab as string} onValueChange={setTab}>
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
        </Tabs>
      </div>
    </div>
  );
};

export default TraceDataViewer;
