import React, { useCallback, useMemo, useState } from "react";
import { Download, Loader2 } from "lucide-react";
import { saveAs } from "file-saver";
import { json2csv } from "json-2-csv";
import isObject from "lodash/isObject";
import isEmpty from "lodash/isEmpty";
import get from "lodash/get";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";
import { generateAnnotationQueueIdFilter } from "@/lib/filters";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { Trace, Thread } from "@/types/traces";
import {
  getFeedbackScoresByUser,
  getCommentsByUser,
} from "@/lib/annotation-queues";
import { prettifyMessage } from "@/lib/traces";
import { JsonNode } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const MAX_EXPORT_ITEMS = 15000;
const MESSAGES_KEYS = ["input", "output", "first_message", "last_message"];

type ExportAnnotatedDataButtonProps = {
  annotationQueue: AnnotationQueue;
  disabled?: boolean;
};

interface ReviewerData {
  comment?: string;
  [key: string]: string | number | undefined;
}

interface ExportTraceData {
  id: string;
  input: JsonNode;
  output: JsonNode;
  metadata: object;
  [reviewerName: string]: unknown;
}

interface ExportThreadData {
  id: string;
  first_message: JsonNode;
  last_message: JsonNode;
  [reviewerName: string]: unknown;
}

const ExportAnnotatedDataButton: React.FC<ExportAnnotatedDataButtonProps> = ({
  annotationQueue,
  disabled = false,
}) => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const reviewers = useMemo(
    () => annotationQueue.reviewers?.map((r) => r.username) ?? [],
    [annotationQueue.reviewers],
  );

  const feedbackDefinitionNames = useMemo(
    () => annotationQueue.feedback_definition_names ?? [],
    [annotationQueue.feedback_definition_names],
  );

  const annotationQueueFilter = useMemo(
    () => generateAnnotationQueueIdFilter(annotationQueue?.id),
    [annotationQueue?.id],
  );

  const { refetch: refetchTraces } = useTracesList(
    {
      projectId: annotationQueue.project_id,
      page: 1,
      size: MAX_EXPORT_ITEMS,
      sorting: [],
      filters: annotationQueueFilter,
      search: "",
      truncate: false,
    },
    {
      enabled: false,
    },
  );

  const { refetch: refetchThreads } = useThreadsList(
    {
      projectId: annotationQueue.project_id,
      page: 1,
      size: MAX_EXPORT_ITEMS,
      sorting: [],
      filters: annotationQueueFilter,
      search: "",
      truncate: false,
    },
    {
      enabled: false,
    },
  );

  const getTracesExportData = useCallback(
    (traces: Trace[]): ExportTraceData[] => {
      if (!traces?.length) return [];

      return traces.map((trace: Trace) => {
        const baseData: ExportTraceData = {
          id: trace.id,
          input: prettifyMessage(trace.input, { type: "input" })
            .message as JsonNode,
          output: prettifyMessage(trace.output, { type: "output" })
            .message as JsonNode,
          metadata: trace.metadata ?? {},
        };

        reviewers.forEach((reviewerName) => {
          const reviewerData: ReviewerData = {};

          const comments = getCommentsByUser(trace.comments, reviewerName);
          if (comments.length > 0) {
            reviewerData.comment = comments.join("; ");
          }

          const feedbackScores = getFeedbackScoresByUser(
            trace.feedback_scores ?? [],
            reviewerName,
            feedbackDefinitionNames,
          );

          feedbackScores.forEach((score) => {
            reviewerData[score.name] = score.value;
            if (score.reason) {
              reviewerData[`${score.name}.reason`] = score.reason;
            }
          });

          if (!isEmpty(reviewerData)) {
            baseData[reviewerName] = reviewerData;
          }
        });

        return baseData;
      });
    },
    [reviewers, feedbackDefinitionNames],
  );

  const getThreadsExportData = useCallback(
    (threads: Thread[]): ExportThreadData[] => {
      if (!threads?.length) return [];

      return threads.map((thread: Thread) => {
        const baseData: ExportThreadData = {
          id: thread.id,
          first_message: prettifyMessage(thread.first_message, {
            type: "input",
          }).message as JsonNode,
          last_message: prettifyMessage(thread.last_message, { type: "output" })
            .message as JsonNode,
        };

        reviewers.forEach((reviewerName) => {
          const reviewerData: ReviewerData = {};

          const comments = getCommentsByUser(thread.comments, reviewerName);
          if (comments.length > 0) {
            reviewerData.comment = comments.join("; ");
          }

          const feedbackScores = getFeedbackScoresByUser(
            thread.feedback_scores ?? [],
            reviewerName,
            feedbackDefinitionNames,
          );

          feedbackScores.forEach((score) => {
            reviewerData[score.name] = score.value;
            if (score.reason) {
              reviewerData[`${score.name}.reason`] = score.reason;
            }
          });

          if (!isEmpty(reviewerData)) {
            baseData[reviewerName] = reviewerData;
          }
        });

        return baseData;
      });
    },
    [reviewers, feedbackDefinitionNames],
  );

  const getData = useCallback(async () => {
    if (annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE) {
      const result = await refetchTraces();
      return getTracesExportData(result.data?.content ?? []);
    } else {
      const result = await refetchThreads();
      return getThreadsExportData(result.data?.content ?? []);
    }
  }, [
    annotationQueue.scope,
    refetchTraces,
    refetchThreads,
    getTracesExportData,
    getThreadsExportData,
  ]);

  const generateFileName = useCallback(
    (extension: string) => {
      const scope = annotationQueue.scope.toLowerCase();
      return `annotated-${scope}-${annotationQueue.name}.${extension}`;
    },
    [annotationQueue.name, annotationQueue.scope],
  );

  const handleExport = useCallback(
    async (
      exportFn: (data: Array<ExportTraceData | ExportThreadData>) => void,
    ) => {
      setLoading(true);
      try {
        const data = await getData();
        if (data.length === 0) {
          toast({
            title: "No data to export",
            description:
              "There are no items in the annotation queue to export.",
            variant: "default",
          });
          return;
        }
        exportFn(data);
        setOpen(false);
      } catch (error) {
        const message = get(
          error,
          ["response", "data", "message"],
          get(error, "message", "Failed to fetch data for export"),
        );
        toast({
          title: "Export failed",
          description: message,
          variant: "destructive",
        });
      } finally {
        setLoading(false);
      }
    },
    [getData, toast],
  );

  const exportCSVHandler = useCallback(() => {
    handleExport((data) => {
      const processedData = data.map((row) => {
        return {
          ...row,
          ...MESSAGES_KEYS.reduce(
            (acc, key) => {
              if (isObject(row[key])) {
                acc[key] = JSON.stringify(row[key], null, 2);
              }
              return acc;
            },
            {} as Record<string, unknown>,
          ),
        };
      });

      const blob = new Blob(
        [
          json2csv(processedData, {
            arrayIndexesAsKeys: true,
            escapeHeaderNestedDots: false,
          }),
        ],
        {
          type: "text/csv;charset=utf-8",
        },
      );
      saveAs(blob, generateFileName("csv"));
    });
  }, [handleExport, generateFileName]);

  const exportJSONHandler = useCallback(() => {
    handleExport((data) => {
      const blob = new Blob([JSON.stringify(data, null, 2)], {
        type: "application/json;charset=utf-8;",
      });
      saveAs(blob, generateFileName("json"));
    });
  }, [handleExport, generateFileName]);

  const handleOpenChange = useCallback(
    (newOpen: boolean) => {
      if ((disabled || !isExportEnabled) && newOpen) return;
      setOpen(newOpen);
    },
    [disabled, isExportEnabled],
  );

  const tooltipContent = !isExportEnabled
    ? "Export functionality is disabled for this installation"
    : "Export annotated data";

  const isButtonDisabled = disabled || loading || !isExportEnabled;

  const buttonElement = (
    <Button variant="outline" size="sm" disabled={isButtonDisabled}>
      {loading ? (
        <Loader2 className="mr-1.5 size-3.5 animate-spin" />
      ) : (
        <Download className="mr-1.5 size-3.5" />
      )}
      Export queue
    </Button>
  );

  return (
    <DropdownMenu open={open} onOpenChange={handleOpenChange}>
      {isButtonDisabled && !isExportEnabled ? (
        <TooltipWrapper content={tooltipContent}>
          <DropdownMenuTrigger asChild>
            <span className="inline-block cursor-not-allowed">
              {buttonElement}
            </span>
          </DropdownMenuTrigger>
        </TooltipWrapper>
      ) : (
        <TooltipWrapper content={tooltipContent}>
          <DropdownMenuTrigger asChild>{buttonElement}</DropdownMenuTrigger>
        </TooltipWrapper>
      )}
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuItem
          onClick={exportCSVHandler}
          disabled={disabled || loading || !isExportEnabled}
        >
          As CSV
        </DropdownMenuItem>
        <DropdownMenuItem
          onClick={exportJSONHandler}
          disabled={disabled || loading || !isExportEnabled}
        >
          As JSON
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ExportAnnotatedDataButton;
