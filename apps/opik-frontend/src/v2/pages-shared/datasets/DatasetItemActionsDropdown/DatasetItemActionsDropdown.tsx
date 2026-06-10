import React from "react";
import { Copy, MoreHorizontal, Share, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type DatasetItemActionsDropdownProps = {
  datasetItemId: string;
  onShare: () => void;
  onCopyId: () => void;
  onDelete: () => void;
};

const DatasetItemActionsDropdown: React.FC<DatasetItemActionsDropdownProps> = ({
  datasetItemId,
  onShare,
  onCopyId,
  onDelete,
}) => {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-2xs">
          <span className="sr-only">Actions menu</span>
          <MoreHorizontal />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuItem onClick={onShare}>
          <Share className="mr-2 size-4" />
          Share item
        </DropdownMenuItem>
        <TooltipWrapper content={datasetItemId} side="left">
          <DropdownMenuItem onClick={onCopyId}>
            <Copy className="mr-2 size-4" />
            Copy item ID
          </DropdownMenuItem>
        </TooltipWrapper>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={onDelete}>
          <Trash className="mr-2 size-4" />
          Delete item
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DatasetItemActionsDropdown;
