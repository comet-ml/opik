import React, { useCallback, useState } from "react";
import copy from "clipboard-copy";
import {
  Copy,
  MessagesSquare,
  MoreHorizontal,
  Share,
  Trash,
} from "lucide-react";

import useTraceDeleteMutation from "@/api/traces/useTraceDeleteMutation";
import { useToast } from "@/components/ui/use-toast";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { OnChangeFn } from "@/types/shared";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import ExpandableSearchInput from "@/components/shared/ExpandableSearchInput/ExpandableSearchInput";

const PANEL_ELEMENTS_EXPANDED_SIZE = [
  { name: "SEPARATOR", size: 25 },
  { name: "GO_TO_THREAD", size: 110 },
  { name: "PADDING", size: 16 },
  { name: "SEPARATOR", size: 25 },
  { name: "MORE", size: 32 },
];

const MIN_PANEL_WIDTH = PANEL_ELEMENTS_EXPANDED_SIZE.reduce(
  (acc, e) => acc + e.size,
  0,
);

const SEARCH_SPACE_RESERVATION = 200;

type TraceDetailsActionsPanelProps = {
  projectId: string;
  traceId: string;
  spanId: string;
  threadId?: string;
  setThreadId?: OnChangeFn<string | null | undefined>;
  onClose: () => void;
  isSpansLazyLoading: boolean;
  search?: string;
  setSearch: OnChangeFn<string | undefined>;
};

const TraceDetailsActionsPanel: React.FunctionComponent<
  TraceDetailsActionsPanelProps
> = ({
  projectId,
  traceId,
  spanId,
  threadId,
  setThreadId,
  onClose,
  isSpansLazyLoading,
  search,
  setSearch,
}) => {
  const [popupOpen, setPopupOpen] = useState<boolean>(false);
  const [isSmall, setIsSmall] = useState<boolean>(false);
  const { toast } = useToast();

  const { mutate } = useTraceDeleteMutation();

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    setIsSmall(node.clientWidth < MIN_PANEL_WIDTH + SEARCH_SPACE_RESERVATION);
  });

  const handleTraceDelete = useCallback(() => {
    onClose();
    mutate({
      traceId,
      projectId,
    });
  }, [onClose, mutate, traceId, projectId]);

  return (
    <div ref={ref} className="flex flex-auto items-center justify-between">
      {setThreadId && threadId ? (
        <div className="flex items-center">
          <Separator orientation="vertical" className="mx-3 h-4" />
          <TooltipWrapper content="Go to thread">
            <Button
              variant="outline"
              size={isSmall ? "icon-sm" : "sm"}
              onClick={() => setThreadId(threadId)}
            >
              {isSmall ? <MessagesSquare /> : "Go to thread"}
            </Button>
          </TooltipWrapper>
        </div>
      ) : (
        <div />
      )}
      <div className="flex items-center gap-2 pl-6">
        <div className="flex min-w-44 max-w-56 flex-auto justify-end overflow-hidden">
          <ExpandableSearchInput
            value={search}
            placeholder="Search by all fields"
            onChange={setSearch}
            disabled={isSpansLazyLoading}
          />
        </div>
        <Separator orientation="vertical" className="mx-1 h-4" />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon-sm">
              <span className="sr-only">Actions menu</span>
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            <DropdownMenuItem
              onClick={() => {
                toast({
                  description: "URL copied to clipboard",
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
                    description: `Trace ID copied to clipboard`,
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
                      description: `Span ID copied to clipboard`,
                    });
                    copy(spanId);
                  }}
                >
                  <Copy className="mr-2 size-4" />
                  Copy span ID
                </DropdownMenuItem>
              </TooltipWrapper>
            )}
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setPopupOpen(true)}>
              <Trash className="mr-2 size-4" />
              Delete trace
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <ConfirmDialog
          open={popupOpen}
          setOpen={setPopupOpen}
          onConfirm={handleTraceDelete}
          title="Delete trace"
          description="Deleting a trace will also remove the trace data from related experiment samples. This action can’t be undone. Are you sure you want to continue?"
          confirmText="Delete trace"
          confirmButtonVariant="destructive"
        />
      </div>
    </div>
  );
};

export default TraceDetailsActionsPanel;
