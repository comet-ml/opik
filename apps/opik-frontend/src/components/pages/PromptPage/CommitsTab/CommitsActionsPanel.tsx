import React, { useRef, useState } from "react";
import { Split, Tag } from "lucide-react";

import { Button } from "@/components/ui/button";
import { PromptVersion } from "@/types/prompts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ComparePromptVersionDialog from "@/components/pages/PromptPage/CommitsTab/ComparePromptVersionDialog";
import AddTagDialog from "@/components/pages/PromptPage/CommitsTab/AddTagDialog";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

type CommitsActionsPanelsProps = {
  versions: PromptVersion[];
};

const CommitsActionsPanel: React.FunctionComponent<
  CommitsActionsPanelsProps
> = ({ versions }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<number | boolean>(false);
  const disabled = versions?.length === 0;
  const compareDisabled = versions?.length < 2;

  return (
    <div className="flex items-center gap-2">
      <ComparePromptVersionDialog
        key={`compare-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        versions={versions}
      />
      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        rows={versions}
        open={open === 2}
        setOpen={setOpen}
      />
      <TooltipWrapper content="Manage version tags">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(2);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag />
        </Button>
      </TooltipWrapper>
      <TooltipWrapper content="Compare commits">
        <Button
          size="sm"
          onClick={() => {
            setOpen(1);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={compareDisabled}
        >
          <Split className="mr-1.5 size-3.5" />
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
