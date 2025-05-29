import React, { useRef, useState } from "react";
import { Split } from "lucide-react";

import { Button } from "@/components/ui/button";
import { PromptVersion } from "@/types/prompts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ComparePromptVersionDialog from "@/components/pages/PromptPage/CommitsTab/ComparePromptVersionDialog";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

type CommitsActionsPanelsProps = {
  versions: PromptVersion[];
};

const CommitsActionsPanel: React.FunctionComponent<
  CommitsActionsPanelsProps
> = ({ versions }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = versions?.length < 2;

  return (
    <div className="flex items-center gap-2">
      <ComparePromptVersionDialog
        key={resetKeyRef.current}
        open={open}
        setOpen={setOpen}
        versions={versions}
      />
      <TooltipWrapper content="Compare commits">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setOpen(true)}
          disabled={disabled}
        >
          <Split className="mr-2 size-3.5" />
          Compare
        </Button>
      </TooltipWrapper>
      <ExplainerIcon
        className="-ml-0.5"
        {...EXPLAINERS_MAP[EXPLAINER_ID.why_would_i_compare_commits]}
      />
    </div>
  );
};

export default CommitsActionsPanel;
