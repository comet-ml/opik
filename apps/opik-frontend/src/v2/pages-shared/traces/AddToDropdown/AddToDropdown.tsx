import React, { useRef, useState } from "react";
import { ChevronDown, Database, UserPen } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Span, Trace, Thread } from "@/types/traces";
import AddToDatasetDialog from "@/v2/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import AddToQueueDialog from "@/v2/pages-shared/traces/AddToQueueDialog/AddToQueueDialog";
import { usePermissions } from "@/contexts/PermissionsContext";

export type AddToDropdownProps = {
  getDataForExport: () => Promise<Array<Trace | Span | Thread>>;
  selectedRows: Array<Trace | Span | Thread>;
  disabled?: boolean;
  dataType?: "traces" | "spans" | "threads";
};

const AddToDropdown: React.FunctionComponent<AddToDropdownProps> = (props) => {
  const { selectedRows, disabled = false, dataType = "traces" } = props;
  // getDataForExport is accepted for backwards compatibility but no longer used
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<number>(0);

  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const isThread = dataType === "threads";
  const isSpan = dataType === "spans";
  const showAddToDataset = !isThread && canViewDatasets;
  const showAddToQueue = isThread || !isSpan;

  if (!showAddToDataset && !showAddToQueue) {
    return null;
  }

  return (
    <>
      {showAddToDataset && (
        <AddToDatasetDialog
          key={`dataset-${resetKeyRef.current}`}
          selectedRows={selectedRows as Array<Trace | Span>}
          open={open === 1}
          setOpen={() => setOpen(0)}
        />
      )}
      {showAddToQueue && (
        <AddToQueueDialog
          key={`queue-${resetKeyRef.current}`}
          open={open === 2}
          setOpen={() => setOpen(0)}
          rows={selectedRows as Array<Thread | Trace>}
        />
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            disabled={disabled}
            className="font-normal"
          >
            Add to
            <ChevronDown className="ml-2 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-60">
          {showAddToDataset && (
            <DropdownMenuItem
              onClick={() => {
                setOpen(1);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <Database className="mr-2 size-4" />
              Evaluation suite
            </DropdownMenuItem>
          )}
          {showAddToQueue && (
            <DropdownMenuItem
              onClick={() => {
                setOpen(2);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <UserPen className="mr-2 size-4" />
              Annotation queue
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
};

export default AddToDropdown;
