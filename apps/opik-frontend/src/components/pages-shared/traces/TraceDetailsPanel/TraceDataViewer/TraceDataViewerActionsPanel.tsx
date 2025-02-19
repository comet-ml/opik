import React, { useMemo, useRef, useState } from "react";
import copy from "clipboard-copy";
import {
  Copy,
  Database,
  MessageSquareMore,
  MoreHorizontal,
  PenLine,
  Share,
} from "lucide-react";

import { Span, Trace } from "@/types/traces";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import { LastSection, LastSectionValue } from "../TraceDetailsPanel";
import { cn } from "@/lib/utils";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";

type TraceDataViewerActionsPanelProps = {
  data: Trace | Span;
  traceId: string;
  spanId?: string;
  lastSection?: LastSectionValue | null;
  setLastSection: (v: LastSectionValue) => void;
};

const TraceDataViewerActionsPanel: React.FunctionComponent<
  TraceDataViewerActionsPanelProps
> = ({ data, traceId, spanId, lastSection, setLastSection }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const { toast } = useToast();

  const rows = useMemo(() => (data ? [data] : []), [data]);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const annotationCount = feedbackDefinitionsData?.content?.length;
  const commentsCount = data.comments?.length;

  return (
    <div className="flex flex-nowrap gap-2">
      <AddToDatasetDialog
        key={resetKeyRef.current}
        rows={rows}
        open={open}
        setOpen={setOpen}
      />
      <Button
        variant="outline"
        size="sm"
        onClick={() => {
          setOpen(true);
          resetKeyRef.current = resetKeyRef.current + 1;
        }}
      >
        <Database className="size-4" />
        <div className="hidden 3xl:block 3xl:pl-1">Add to dataset</div>
      </Button>

      <Button
        variant="outline"
        size="sm"
        onClick={() => setLastSection(LastSection.Comments)}
        className={cn(
          "gap-1",
          lastSection === LastSection.Comments &&
            "bg-primary-100 hover:bg-primary-100",
        )}
      >
        <MessageSquareMore className="size-4" />
        <div className="hidden 3xl:block 3xl:pl-1">Comments</div>
        {Boolean(commentsCount) && <div>{commentsCount}</div>}
      </Button>

      <Button
        variant="outline"
        size="sm"
        onClick={() => setLastSection(LastSection.Annotations)}
        className={cn(
          "gap-1",
          lastSection === LastSection.Annotations &&
            "bg-primary-100 hover:bg-primary-100",
        )}
      >
        <PenLine className="size-4" />
        <div className="hidden 3xl:block 3xl:pl-1">Feedback scores</div>
        {Boolean(annotationCount) && <div>{annotationCount}</div>}
      </Button>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-sm">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              toast({
                description: "URL successfully copied to clipboard",
              });
              copy(window.location.href);
            }}
          >
            <Share className="mr-2 size-4" />
            Share
          </DropdownMenuItem>
          <TooltipWrapper content={traceId} side="left">
            <DropdownMenuItem
              onClick={() => {
                toast({
                  description: `Trace ID successfully copied to clipboard`,
                });
                copy(traceId);
              }}
            >
              <Copy className="mr-2 size-4" />
              Copy trace ID
            </DropdownMenuItem>
          </TooltipWrapper>
          {spanId && (
            <TooltipWrapper content={spanId} side="left">
              <DropdownMenuItem
                onClick={() => {
                  toast({
                    description: `Span ID successfully copied to clipboard`,
                  });
                  copy(spanId);
                }}
              >
                <Copy className="mr-2 size-4" />
                Copy span ID
              </DropdownMenuItem>
            </TooltipWrapper>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default TraceDataViewerActionsPanel;
