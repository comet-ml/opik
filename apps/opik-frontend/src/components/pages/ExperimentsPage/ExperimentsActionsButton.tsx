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
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type ExperimentsActionsButtonProps = {
  experiments: Experiment[];
};

const ExperimentsActionsButton: React.FunctionComponent<
  ExperimentsActionsButtonProps
> = ({ experiments }) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const handleCompareClick = () => {
    if (experiments.length === 0) return;

    navigate({
      to: "/$workspaceName/experiments/$datasetId/compare",
      params: {
        datasetId: experiments[0].dataset_id,
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

export default ExperimentsActionsButton;
