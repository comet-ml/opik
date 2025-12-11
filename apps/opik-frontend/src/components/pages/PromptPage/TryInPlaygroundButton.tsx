import React, { useCallback, useRef, useState } from "react";
import { Play } from "lucide-react";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import { parsePromptVersionContent } from "@/lib/llm";

type TryInPlaygroundButtonProps = {
  prompt?: PromptWithLatestVersion;
  activeVersion?: PromptVersion;
  ButtonComponent?: React.ComponentType<{
    variant?: string;
    size?: string;
    disabled?: boolean;
    onClick?: () => void;
    children: React.ReactNode;
    className?: string;
  }>;
};

const TryInPlaygroundButton: React.FC<TryInPlaygroundButtonProps> = ({
  prompt,
  activeVersion,
  ButtonComponent = Button,
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
      templateStructure: prompt?.template_structure,
    });
  }, [loadPlayground, prompt, activeVersion]);

  return (
    <>
      <ButtonComponent
        variant="outline"
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
        <Play className="mr-1.5 size-3.5" />
        Try in the Playground
      </ButtonComponent>
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

export default TryInPlaygroundButton;
