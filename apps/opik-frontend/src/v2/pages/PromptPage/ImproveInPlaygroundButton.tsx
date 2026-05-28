import React, { useCallback, useMemo, useState } from "react";
import { Wand2 } from "lucide-react";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { MessageContent } from "@/types/llm";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import PromptImprovementDialog from "@/v2/pages-shared/llm/PromptImprovementDialog/PromptImprovementDialog";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { parseLLMMessageContent, parsePromptVersionContent } from "@/lib/llm";
import { usePermissions } from "@/contexts/PermissionsContext";

type ImproveInPlaygroundButtonProps = {
  prompt?: PromptWithLatestVersion;
  activeVersion?: PromptVersion;
};

const EMPTY_CONFIGS: LLMPromptConfigsType = {};

const ImproveInPlaygroundButton: React.FC<ImproveInPlaygroundButtonProps> = ({
  prompt,
  activeVersion,
}) => {
  const [open, setOpen] = useState(false);

  const workspaceName = useAppStore((s) => s.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();
  const { mutate: createVersion } = useCreatePromptVersionMutation();
  const {
    permissions: { canCreatePrompts },
  } = usePermissions();

  const sourceVersion = activeVersion ?? prompt?.latest_version;
  const originalPrompt = useMemo<MessageContent>(
    () => parsePromptVersionContent(sourceVersion),
    [sourceVersion],
  );

  const handleAccept = useCallback(
    (_messageId: string, improvedPrompt: MessageContent) => {
      if (!prompt || !canCreatePrompts) return;
      const template =
        typeof improvedPrompt === "string"
          ? improvedPrompt
          : parseLLMMessageContent(improvedPrompt).text ?? "";
      createVersion({
        name: prompt.name,
        template,
        templateStructure: prompt.template_structure,
        type: sourceVersion?.type,
        metadata: sourceVersion?.metadata,
        projectId: activeProjectId ?? undefined,
        onSuccess: () => {
          toast({ description: `Saved improved version of ${prompt.name}` });
          setOpen(false);
        },
      });
    },
    [
      prompt,
      canCreatePrompts,
      sourceVersion?.type,
      sourceVersion?.metadata,
      activeProjectId,
      createVersion,
      toast,
    ],
  );

  return (
    <>
      <TooltipWrapper content="Improve this prompt with AI">
        <Button
          variant="ghost"
          size="sm"
          className="px-0"
          disabled={!prompt}
          onClick={() => setOpen(true)}
        >
          <Wand2 className="mr-1.5 size-3.5" />
          Improve
        </Button>
      </TooltipWrapper>
      <PromptImprovementDialog
        open={open}
        setOpen={setOpen}
        id={sourceVersion?.id ?? prompt?.id ?? "improve-prompt"}
        originalPrompt={originalPrompt}
        model=""
        provider={"" as COMPOSED_PROVIDER_TYPE}
        configs={EMPTY_CONFIGS}
        workspaceName={workspaceName}
        onAccept={handleAccept}
      />
    </>
  );
};

export default ImproveInPlaygroundButton;
