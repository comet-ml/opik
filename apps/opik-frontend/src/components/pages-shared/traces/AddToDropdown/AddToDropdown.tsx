import React, { useRef, useState } from "react";
import { ChevronDown, Database, UserPen } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Span, Trace, Thread } from "@/types/traces";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import AddToQueueDialog from "@/components/pages-shared/traces/AddToQueueDialog/AddToQueueDialog";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DISABLED_DATASETS_TOOLTIP } from "@/constants/permissions";

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
  const showAddToDataset = !isThread;
  const showAddToQueue = isThread || !isSpan;
  const isDatasetSingleActionDisabled =
    !canViewDatasets && showAddToDataset && !showAddToQueue;

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
        <Tooltip delayDuration={100}>
          <TooltipTrigger asChild>
            <div>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={disabled || isDatasetSingleActionDisabled}
                  className="font-normal"
                >
                  Add to
                  <ChevronDown className="ml-2 size-4" />
                </Button>
              </DropdownMenuTrigger>
            </div>
          </TooltipTrigger>
          {isDatasetSingleActionDisabled && (
            <TooltipContent side="top">
              {DISABLED_DATASETS_TOOLTIP}
            </TooltipContent>
          )}
        </Tooltip>
        <DropdownMenuContent align="start" className="w-60">
          {showAddToDataset && (
            <Tooltip delayDuration={100}>
              <TooltipTrigger asChild>
                <div>
                  <DropdownMenuItem
                    onClick={() => {
                      setOpen(1);
                      resetKeyRef.current = resetKeyRef.current + 1;
                    }}
                    disabled={disabled || !canViewDatasets}
                  >
                    <Database className="mr-2 size-4" />
                    Dataset
                  </DropdownMenuItem>
                </div>
              </TooltipTrigger>
              {!canViewDatasets && (
                <TooltipContent side="left">
                  {DISABLED_DATASETS_TOOLTIP}
                </TooltipContent>
              )}
            </Tooltip>
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
