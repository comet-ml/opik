import React, { useCallback, useMemo, useState } from "react";
import { Download } from "lucide-react";
import { saveAs } from "file-saver";
import { json2csv } from "json-2-csv";
import isObject from "lodash/isObject";
import isEmpty from "lodash/isEmpty";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { createFilter } from "@/lib/filters";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import { keepPreviousData } from "@tanstack/react-query";
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

  const reviewers = useMemo(
    () => annotationQueue.reviewers?.map((r) => r.username) ?? [],
    [annotationQueue.reviewers],
  );

  const feedbackDefinitionNames = useMemo(
    () => annotationQueue.feedback_definition_names ?? [],
    [annotationQueue.feedback_definition_names],
  );

  const annotationQueueFilter = useMemo(() => {
    if (!annotationQueue?.id) return [];
    return [
      createFilter({
        field: "annotation_queue_ids",
        value: annotationQueue.id,
        operator: "contains",
      }),
    ];
  }, [annotationQueue?.id]);

  const { data: tracesData, isLoading: isTracesLoading } = useTracesList(
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
      enabled:
        annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE &&
        !!annotationQueue.project_id,
      placeholderData: keepPreviousData,
    },
  );

  const { data: threadsData, isLoading: isThreadsLoading } = useThreadsList(
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
      enabled:
        annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.THREAD &&
        !!annotationQueue.project_id,
      placeholderData: keepPreviousData,
    },
  );

  const isLoading = isTracesLoading || isThreadsLoading;

  const getTracesExportData = useCallback((): ExportTraceData[] => {
    if (!tracesData?.content) return [];

    return tracesData.content.map((trace: Trace) => {
      const baseData: ExportTraceData = {
        id: trace.id,
        input: prettifyMessage(trace.input).message as JsonNode,
        output: prettifyMessage(trace.output).message as JsonNode,
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
  }, [tracesData, reviewers, feedbackDefinitionNames]);

  const getThreadsExportData = useCallback((): ExportThreadData[] => {
    if (!threadsData?.content) return [];

    return threadsData.content.map((thread: Thread) => {
      const baseData: ExportThreadData = {
        id: thread.id,
        first_message: prettifyMessage(thread.first_message)
          .message as JsonNode,
        last_message: prettifyMessage(thread.last_message).message as JsonNode,
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
  }, [threadsData, reviewers, feedbackDefinitionNames]);

  const getData = useCallback(() => {
    if (annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE) {
      return getTracesExportData();
    }
    return getThreadsExportData();
  }, [annotationQueue.scope, getTracesExportData, getThreadsExportData]);

  const generateFileName = useCallback(
    (extension: string) => {
      const scope = annotationQueue.scope.toLowerCase();
      return `annotated-${scope}-${annotationQueue.name}.${extension}`;
    },
    [annotationQueue.name, annotationQueue.scope],
  );

  const exportCSVHandler = useCallback(() => {
    const data = getData();
    if (data.length === 0) {
      return;
    }

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
    setOpen(false);
  }, [getData, generateFileName]);

  const exportJSONHandler = useCallback(() => {
    const data = getData();
    if (data.length === 0) {
      return;
    }

    const blob = new Blob([JSON.stringify(data, null, 2)], {
      type: "application/json;charset=utf-8;",
    });
    saveAs(blob, generateFileName("json"));
    setOpen(false);
  }, [getData, generateFileName]);

  const hasData =
    (tracesData?.content?.length ?? 0) > 0 ||
    (threadsData?.content?.length ?? 0) > 0;

  return (
    <TooltipWrapper content="Export annotated data">
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            disabled={
              disabled || isLoading || !hasData || reviewers.length === 0
            }
          >
            <Download className="mr-1.5 size-3.5" />
            Export
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={exportCSVHandler}>As CSV</DropdownMenuItem>
          <DropdownMenuItem onClick={exportJSONHandler}>
            As JSON
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </TooltipWrapper>
  );
};

export default ExportAnnotatedDataButton;
