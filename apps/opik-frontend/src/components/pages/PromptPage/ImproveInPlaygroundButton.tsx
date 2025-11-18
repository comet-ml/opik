import React, { useCallback, useRef, useState } from "react";
import { Wand2 } from "lucide-react";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import { parsePromptVersionContent } from "@/lib/llm";

type ImproveInPlaygroundButtonProps = {
  prompt?: PromptWithLatestVersion;
  activeVersion?: PromptVersion;
};

const ImproveInPlaygroundButton: React.FC<ImproveInPlaygroundButtonProps> = ({
  prompt,
  activeVersion,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const handleLoadPlayground = useCallback(() => {
    loadPlayground({
      promptContent: parsePromptVersionContent(
        activeVersion ?? prompt?.latest_version,
      ),
      promptId: prompt?.id,
      promptVersionId: activeVersion?.id ?? prompt?.latest_version?.id,
      autoImprove: true,
      templateStructure: prompt?.template_structure,
    });
  }, [loadPlayground, prompt, activeVersion]);

  return (
    <>
      <TooltipWrapper content="Opens the prompt in the Playground for improvement">
        <Button
          variant="secondary"
          size="sm"
          disabled={!prompt || isPendingProviderKeys}
          onClick={() => {
            if (isPlaygroundEmpty) {
              handleLoadPlayground();
            } else {
              resetKeyRef.current = resetKeyRef.current + 1;
              setOpen(true);
            }
          }}
        >
          <Wand2 className="mr-1.5 size-3.5" />
          Improve prompt
        </Button>
      </TooltipWrapper>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={Boolean(open)}
        setOpen={setOpen}
        onConfirm={handleLoadPlayground}
        title="Load prompt"
        description="Loading this prompt into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Load prompt"
      />
    </>
  );
};

export default ImproveInPlaygroundButton;
