import React, { useCallback, useMemo, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Play, Copy, Trash } from "lucide-react";
import copy from "clipboard-copy";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import OpenInPlaygroundDialog from "@/components/pages-shared/traces/OpenInPlaygroundDialog";
import { useToast } from "@/components/ui/use-toast";
import useOpenInPlayground from "@/hooks/useOpenInPlayground";
import useTraceDeleteMutation from "@/api/traces/useTraceDeleteMutation";
import useSpansList from "@/api/traces/useSpansList";
import { canOpenInPlayground } from "@/lib/playground/extractPlaygroundData";
import { Span, Trace } from "@/types/traces";

type CustomMeta = {
  projectId: string;
};

const MAX_SPANS_LOAD_SIZE = 500;

const TraceRowActionsCell: React.FC<CellContext<Trace | Span, unknown>> = (
  context,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { projectId } = (custom ?? {}) as CustomMeta;

  const resetKeyRef = useRef(0);
  const item = context.row.original;
  const [deleteOpen, setDeleteOpen] = useState<boolean>(false);
  const [playgroundOpen, setPlaygroundOpen] = useState<boolean>(false);

  const { toast } = useToast();
  const { mutate: deleteTrace } = useTraceDeleteMutation();
  const { openInPlayground, isPlaygroundEmpty } = useOpenInPlayground();

  // Check if this is a trace (not a span)
  const isTrace = !("trace_id" in item);
  const traceId = isTrace ? item.id : (item as Span).trace_id;

  // Fetch spans when dialog is open (for full context)
  const { data: spansData, isPending: isSpansLoading } = useSpansList(
    {
      projectId,
      traceId,
      page: 1,
      size: MAX_SPANS_LOAD_SIZE,
    },
    {
      enabled: playgroundOpen && Boolean(traceId) && Boolean(projectId),
    },
  );

  // Combine trace/span with its spans for full tree data
  const fullTreeData = useMemo(() => {
    const spans = spansData?.content || [];
    // If we have the trace, include it with its spans
    if (isTrace) {
      return [item, ...spans];
    }
    // If we have a span, include all spans from the same trace
    return spans;
  }, [item, spansData?.content, isTrace]);

  // Check if can open in playground
  const canOpen = canOpenInPlayground(item);

  const handleDelete = useCallback(() => {
    if (isTrace) {
      deleteTrace({
        traceId: item.id,
        projectId,
      });
    }
  }, [deleteTrace, item.id, projectId, isTrace]);

  const handleCopyId = useCallback(() => {
    copy(item.id);
    toast({
      description: `${isTrace ? "Trace" : "Span"} ID copied to clipboard`,
    });
  }, [item.id, isTrace, toast]);

  const handleOpenInPlayground = useCallback(() => {
    setPlaygroundOpen(true);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleConfirmOpenInPlayground = useCallback(() => {
    // Use full tree data if available, otherwise fall back to just the item
    openInPlayground(item, fullTreeData.length > 1 ? fullTreeData : [item]);
  }, [openInPlayground, item, fullTreeData]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <OpenInPlaygroundDialog
        key={`playground-${resetKeyRef.current}`}
        open={playgroundOpen}
        setOpen={setPlaygroundOpen}
        onConfirm={handleConfirmOpenInPlayground}
        selectedItem={item}
        treeData={fullTreeData.length > 1 ? fullTreeData : [item]}
        isPlaygroundEmpty={isPlaygroundEmpty}
        isLoading={playgroundOpen && isSpansLoading}
      />
      {isTrace && (
        <ConfirmDialog
          key={`delete-${resetKeyRef.current}`}
          open={deleteOpen}
          setOpen={setDeleteOpen}
          onConfirm={handleDelete}
          title="Delete trace"
          description="Deleting a trace will also remove the trace data from related experiment samples. This action can't be undone. Are you sure you want to continue?"
          confirmText="Delete trace"
          confirmButtonVariant="destructive"
        />
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          {canOpen && (
            <>
              <DropdownMenuItem onClick={handleOpenInPlayground}>
                <Play className="mr-2 size-4" />
                Open in Playground
              </DropdownMenuItem>
              <DropdownMenuSeparator />
            </>
          )}
          <TooltipWrapper content={item.id} side="left">
            <DropdownMenuItem onClick={handleCopyId}>
              <Copy className="mr-2 size-4" />
              Copy {isTrace ? "trace" : "span"} ID
            </DropdownMenuItem>
          </TooltipWrapper>
          {isTrace && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                onClick={() => {
                  setDeleteOpen(true);
                  resetKeyRef.current = resetKeyRef.current + 1;
                }}
              >
                <Trash className="mr-2 size-4" />
                Delete trace
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default TraceRowActionsCell;
