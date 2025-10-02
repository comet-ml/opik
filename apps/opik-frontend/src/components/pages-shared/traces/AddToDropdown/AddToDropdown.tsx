import React, { useRef, useState } from "react";
import { ChevronDown, Database, UserPen } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Span, Trace, Thread } from "@/types/traces";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import AddToQueueDialog from "@/components/pages-shared/traces/AddToQueueDialog/AddToQueueDialog";
import { isObjectSpan, isObjectThread } from "@/lib/traces";

export type AddToDropdownProps = {
  rows: Array<Trace | Span | Thread>;
  disabled?: boolean;
};

const AddToDropdown: React.FunctionComponent<AddToDropdownProps> = ({
  rows,
  disabled = false,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<number>(0);

  const isThread = isObjectThread(rows[0]);
  const isSpan = isObjectSpan(rows[0]);
  const showAddToDataset = !isThread;
  const showAddToQueue = isThread || !isSpan;

  return (
    <>
      {showAddToDataset && (
        <AddToDatasetDialog
          key={`dataset-${resetKeyRef.current}`}
          rows={rows as Array<Trace | Span>}
          open={open === 1}
          setOpen={() => setOpen(0)}
        />
      )}
      {showAddToQueue && (
        <AddToQueueDialog
          key={`queue-${resetKeyRef.current}`}
          open={open === 2}
          setOpen={() => setOpen(0)}
          rows={rows as Array<Thread | Trace>}
        />
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" disabled={disabled}>
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
              Dataset
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
