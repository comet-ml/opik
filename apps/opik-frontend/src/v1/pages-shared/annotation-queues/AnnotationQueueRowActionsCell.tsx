import React, { useCallback, useRef, useState } from "react";
import { MoreHorizontal, Copy, Trash, Pencil } from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationQueueDeleteMutation from "@/api/annotation-queues/useAnnotationQueueBatchDeleteMutation";
import useAppStore from "@/store/AppStore";
import { generateSMEURL } from "@/lib/annotation-queues";
import { usePermissions } from "@/contexts/PermissionsContext";
import AddEditAnnotationQueueDialog from "./AddEditAnnotationQueueDialog";

const AnnotationQueueRowActionsCell: React.FunctionComponent<
  CellContext<AnnotationQueue, unknown>
> = (context) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const resetKeyRef = useRef(0);
  const queue = context.row.original;
  const [open, setOpen] = useState<number | boolean>(false);
  const { toast } = useToast();
  const { mutate } = useAnnotationQueueDeleteMutation();

  const {
    permissions: { canDeleteAnnotationQueues },
  } = usePermissions();

  const handleCopySMELink = useCallback(() => {
    copy(generateSMEURL(workspaceName, queue.id));
    toast({
      title: "Annotation queue link copied to clipboard",
      description:
        "Share this queue with your annotators so they can start annotating and provide feedback to improve the evaluation of your LLM application.",
    });
  }, [queue.id, toast, workspaceName]);

  const deleteQueueHandler = useCallback(() => {
    mutate({
      ids: [queue.id],
    });
  }, [queue.id, mutate]);

  const handleEdit = useCallback(() => {
    setOpen(2);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      {canDeleteAnnotationQueues && (
        <ConfirmDialog
          key={`delete-${resetKeyRef.current}`}
          open={open === 1}
          setOpen={setOpen}
          onConfirm={deleteQueueHandler}
          title="Delete annotation queue?"
          description="Deleting an annotation queue will permanently remove it from the system. All associated data and configurations will be lost. This action can't be undone. Are you sure you want to continue?"
          confirmText="Delete"
          confirmButtonVariant="destructive"
        />
      )}
      <AddEditAnnotationQueueDialog
        key={`edit-${resetKeyRef.current}`}
        queue={queue}
        open={open === 2}
        setOpen={setOpen}
        projectId={queue.project_id}
        scope={queue.scope}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={handleCopySMELink}>
            <Copy className="mr-2 size-4" />
            Copy sharing link
          </DropdownMenuItem>
          <DropdownMenuItem onClick={handleEdit}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          {canDeleteAnnotationQueues && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                onClick={() => {
                  setOpen(1);
                  resetKeyRef.current = resetKeyRef.current + 1;
                }}
                variant="destructive"
              >
                <Trash className="mr-2 size-4" />
                Delete
              </DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default AnnotationQueueRowActionsCell;
