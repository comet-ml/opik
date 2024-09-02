import React from "react";
import { Split } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Experiment } from "@/types/datasets";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type DatasetExperimentsActionsButtonProps = {
  experiments: Experiment[];
};

const DatasetExperimentsActionsButton: React.FunctionComponent<
  DatasetExperimentsActionsButtonProps
> = ({ experiments }) => {
  const datasetId = useDatasetIdFromURL();
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleCompareClick = () => {
    navigate({
      to: "/$workspaceName/datasets/$datasetId/compare",
      params: {
        datasetId,
        workspaceName,
      },
      search: {
        experiments: experiments.map((e) => e.id),
      },
    });
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="default">
            {`Actions (${experiments.length} selected)`}{" "}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-52">
          <DropdownMenuItem onClick={handleCompareClick}>
            <Split className="mr-2 size-4" />
            Compare
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
};

export default DatasetExperimentsActionsButton;
