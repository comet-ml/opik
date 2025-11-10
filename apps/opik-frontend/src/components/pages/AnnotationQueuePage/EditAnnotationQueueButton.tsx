import React, { useCallback, useRef, useState } from "react";
import { AnnotationQueue } from "@/types/annotation-queues";
import AddEditAnnotationQueueDialog from "@/components/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import { Button } from "@/components/ui/button";
import { Pencil } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface EditAnnotationQueueButtonProps {
  annotationQueue: AnnotationQueue;
}

const EditAnnotationQueueButton: React.FunctionComponent<
  EditAnnotationQueueButtonProps
> = ({ annotationQueue }) => {
  const resetKeyRef = useRef(0);
  const [isEditDialogOpen, setIsEditDialogOpen] = useState(false);

  const handleOpenEditDialog = useCallback(() => {
    setIsEditDialogOpen(true);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  return (
    <>
      <AddEditAnnotationQueueDialog
        key={`edit-${resetKeyRef.current}`}
        queue={annotationQueue}
        open={isEditDialogOpen}
        setOpen={setIsEditDialogOpen}
        projectId={annotationQueue.project_id}
        scope={annotationQueue.scope}
      />
      <TooltipWrapper content="Edit annotation queue">
        <Button size="sm" variant="outline" onClick={handleOpenEditDialog}>
          <Pencil className="mr-1.5 size-3.5" />
          Edit
        </Button>
      </TooltipWrapper>
    </>
  );
};

export default EditAnnotationQueueButton;
