import React, { useRef, useState } from "react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import AddToDatasetDialog from "@/components/pages/TracesPage/AddToDataset/AddToDatasetDialog";
import { Database } from "lucide-react";

type TracesActionsButtonProps = {
  rows: Array<Trace | Span>;
};

const TracesActionsButton: React.FunctionComponent<
  TracesActionsButtonProps
> = ({ rows }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  return (
    <>
      <AddToDatasetDialog
        key={resetKeyRef.current}
        rows={rows}
        open={open}
        setOpen={setOpen}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="default">
            {`Actions (${rows.length} selected)`}{" "}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Database className="mr-2 size-4" />
            Add to dataset
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
};

export default TracesActionsButton;
