import React, { useMemo, useRef, useState } from "react";
import copy from "clipboard-copy";
import { Copy, Database, MoreHorizontal, PenLine, Share } from "lucide-react";

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
import AddToDatasetDialog from "@/components/pages/TracesPage/AddToDataset/AddToDatasetDialog";

type TraceDataViewerActionsPanelProps = {
  data: Trace | Span;
  traceId: string;
  spanId?: string;
  annotateOpen: boolean;
  setAnnotateOpen: (open: boolean) => void;
};

const TraceDataViewerActionsPanel: React.FunctionComponent<
  TraceDataViewerActionsPanelProps
> = ({ data, traceId, spanId, annotateOpen, setAnnotateOpen }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const { toast } = useToast();

  const rows = useMemo(() => (data ? [data] : []), [data]);

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
        <Database className="mr-2 size-4" />
        Add to dataset
      </Button>
      {!annotateOpen && (
        <Button
          variant="outline"
          size="sm"
          onClick={() => setAnnotateOpen(true)}
        >
          <PenLine className="mr-2 size-4" />
          Annotate
        </Button>
      )}
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
