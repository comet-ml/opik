import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useAnnotationQueueDeleteItemsMutation from "@/api/annotation-queues/useAnnotationQueueDeleteItemsMutation";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { CellContext } from "@tanstack/react-table";
import { Thread, Trace } from "@/types/traces";

type CustomMeta = {
  annotationQueueId: string;
};

const QueueItemRowActionsCell: React.FC<
  CellContext<Thread | Trace, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { annotationQueueId } = (custom ?? {}) as CustomMeta;

  const resetKeyRef = useRef(0);
  const item = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useAnnotationQueueDeleteItemsMutation();

  const deleteItemHandler = useCallback(() => {
    if (annotationQueueId) {
      mutate({
        annotationQueueId,
        ids: [getAnnotationQueueItemId(item)],
      });
    }
  }, [annotationQueueId, mutate, item]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteItemHandler}
        title="Remove from queue"
        description="Removing annotation queue items will remove them from the queue. This action can't be undone. Are you sure you want to continue?"
        confirmText="Remove item"
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5 ">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            variant="destructive"
          >
            <Trash className="mr-2 size-4" />
            Remove from queue
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default QueueItemRowActionsCell;
