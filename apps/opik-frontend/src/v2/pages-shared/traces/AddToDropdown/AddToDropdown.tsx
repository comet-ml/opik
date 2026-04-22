import React, { useRef, useState } from "react";
import {
  ChevronDown,
  Database,
  ListChecks,
  UserPen,
  LucideIcon,
} from "lucide-react";

import { Button, ButtonProps } from "@/ui/button";
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
import { DATASET_TYPE } from "@/types/datasets";

type DatasetOption = {
  datasetType: DATASET_TYPE;
  label: string;
  icon: LucideIcon;
  openValue: number;
};

const DATASET_OPTIONS: DatasetOption[] = [
  {
    datasetType: DATASET_TYPE.TEST_SUITE,
    label: "Test suite",
    icon: ListChecks,
    openValue: 1,
  },
  {
    datasetType: DATASET_TYPE.DATASET,
    label: "Dataset",
    icon: Database,
    openValue: 3,
  },
];

export type AddToDropdownProps = {
  getDataForExport: () => Promise<Array<Trace | Span | Thread>>;
  selectedRows: Array<Trace | Span | Thread>;
  disabled?: boolean;
  dataType?: "traces" | "spans" | "threads";
  buttonVariant?: ButtonProps["variant"];
  buttonSize?: ButtonProps["size"];
};

const AddToDropdown: React.FunctionComponent<AddToDropdownProps> = (props) => {
  const {
    selectedRows,
    disabled = false,
    dataType = "traces",
    buttonVariant = "outline",
    buttonSize = "sm",
  } = props;
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
      {showAddToDataset &&
        DATASET_OPTIONS.map((opt) => (
          <AddToDatasetDialog
            key={`${opt.label}-${resetKeyRef.current}`}
            selectedRows={selectedRows as Array<Trace | Span>}
            datasetType={opt.datasetType}
            open={open === opt.openValue}
            setOpen={() => setOpen(0)}
          />
        ))}
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
            variant={buttonVariant}
            size={buttonSize}
            disabled={disabled}
            className="font-normal"
          >
            Add to
            <ChevronDown className="ml-2 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-60">
          {showAddToDataset &&
            DATASET_OPTIONS.map((opt) => (
              <DropdownMenuItem
                key={opt.label}
                onClick={() => {
                  setOpen(opt.openValue);
                  resetKeyRef.current = resetKeyRef.current + 1;
                }}
                disabled={disabled}
              >
                <opt.icon className="mr-2 size-4" />
                {opt.label}
              </DropdownMenuItem>
            ))}
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
