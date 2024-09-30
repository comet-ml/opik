import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import DeleteDatasetDialog from "@/components/pages/DatasetsPage/DeleteDatasetDialog";
import { Dataset } from "@/types/datasets";

export const DatasetRowActionsCell: React.FunctionComponent<
  CellContext<Dataset, unknown>
> = ({ row }) => {
  const resetKeyRef = useRef(0);
  const dataset = row.original;
  const [open, setOpen] = useState<boolean>(false);

  return (
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <DeleteDatasetDialog
        key={resetKeyRef.current}
        dataset={dataset}
        open={open}
        setOpen={setOpen}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};
