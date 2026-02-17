import React, { useCallback, useRef, useState, useMemo } from "react";
import { Info, Pencil } from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import { Button } from "@/components/ui/button";
import {
  PromptVersion,
  PromptWithLatestVersion,
  PROMPT_TEMPLATE_STRUCTURE,
} from "@/types/prompts";
import Loader from "@/components/shared/Loader/Loader";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";
import UseThisPromptDialog from "@/components/pages/PromptPage/PromptTab/UseThisPromptDialog";
import EditPromptVersionDialog from "@/components/pages/PromptPage/PromptTab/EditPromptVersionDialog";
import CommitHistory from "@/components/pages/PromptPage/PromptTab/CommitHistory";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import TryInPlaygroundButton from "@/components/pages/PromptPage/TryInPlaygroundButton";
import ImproveInPlaygroundButton from "@/components/pages/PromptPage/ImproveInPlaygroundButton";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import RestoreVersionDialog from "./RestoreVersionDialog";
import ChatPromptView from "./ChatPromptView";
import TextPromptView from "./TextPromptView";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import usePromptVersionsUpdateMutation from "@/api/prompts/usePromptVersionsUpdateMutation";

interface PromptTabInterface {
  prompt?: PromptWithLatestVersion;
}

const PromptTab = ({ prompt }: PromptTabInterface) => {
  const [openUseThisPrompt, setOpenUseThisPrompt] = useState(false);
  const [openEditPrompt, setOpenEditPrompt] = useState(false);
  const [versionToRestore, setVersionToRestore] =
    useState<PromptVersion | null>(null);

  const [activeVersionId, setActiveVersionId] = useQueryParam(
    "activeVersionId",
    StringParam,
  );

  const editPromptResetKeyRef = useRef(0);
  const updateVersionsMutation = usePromptVersionsUpdateMutation();

  const { data } = usePromptVersionsById(
    {
      promptId: prompt?.id || "",
      page: 1,
      size: 25,
      sorting: [{ id: "created_at", desc: true }],
    },
    {
      enabled: !!prompt?.id,
      refetchInterval: 30000,
    },
  );

  const versions = data?.content;

  const effectiveVersionId = useMemo(() => {
    if (activeVersionId && versions?.some((v) => v.id === activeVersionId)) {
      return activeVersionId;
    }
    return prompt?.latest_version?.id ?? versions?.[0]?.id ?? "";
  }, [activeVersionId, versions, prompt?.latest_version?.id]);

  const { data: activeVersion } = usePromptVersionById(
    {
      versionId: effectiveVersionId,
    },
    {
      enabled: !!effectiveVersionId,
    },
  );

  const handleOpenEditPrompt = (value: boolean) => {
    editPromptResetKeyRef.current = editPromptResetKeyRef.current + 1;
    setOpenEditPrompt(value);
  };

  const handleRestoreVersionClick = (version: PromptVersion) => {
    setVersionToRestore(version);
  };

  const displayText = activeVersion?.template || "";

  const versionTags = activeVersion?.tags || [];

  const updateVersionTags = useCallback(
    (tags: string[]) => {
      if (!activeVersion?.id) return;
      updateVersionsMutation.mutate({
        versionIds: [activeVersion.id],
        tags,
        mergeTags: false,
      });
    },
    [activeVersion?.id, updateVersionsMutation],
  );

  const isChatPrompt = useMemo(() => {
    return prompt?.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  }, [prompt?.template_structure]);

  if (!prompt) {
    return <Loader />;
  }

  return (
    <div className="px-6">
      <div className="flex w-full items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setOpenUseThisPrompt(true)}
        >
          <Info className="mr-1.5 size-3.5" />
          Use this prompt
        </Button>
        <TryInPlaygroundButton prompt={prompt} activeVersion={activeVersion} />
        {!isChatPrompt && (
          <ImproveInPlaygroundButton
            prompt={prompt}
            activeVersion={activeVersion}
          />
        )}
        <Button
          className="ml-auto"
          size="sm"
          onClick={() => handleOpenEditPrompt(true)}
        >
          <Pencil className="mr-1.5 size-3.5" />
          Edit prompt
        </Button>
      </div>

      <div className="mt-4 flex gap-6 rounded-md border bg-background p-6">
        <div className="flex grow flex-col gap-2">
          {isChatPrompt ? (
            <ChatPromptView template={displayText} />
          ) : (
            <TextPromptView template={displayText} />
          )}
          {activeVersion?.metadata && (
            <>
              <p className="comet-body-s-accented mt-4 text-foreground">
                Metadata
              </p>
              <CodeHighlighter
                data={JSON.stringify(activeVersion.metadata, null, 2)}
                language={SUPPORTED_LANGUAGE.json}
              />
            </>
          )}

          {activeVersion?.change_description && (
            <>
              <p className="comet-body-s-accented mt-4 text-foreground">
                Commit message
              </p>
              <div className="comet-body-s flex w-full whitespace-pre-wrap break-all rounded-md bg-primary-foreground p-3">
                {activeVersion.change_description}
              </div>
            </>
          )}

          <TagListRenderer
            tags={versionTags}
            onAddTag={(newTag) => updateVersionTags([...versionTags, newTag])}
            onDeleteTag={(tag) =>
              updateVersionTags(versionTags.filter((t) => t !== tag))
            }
            align="start"
            tooltipText="Version tags list"
            placeholderText="New version tag"
            addButtonText="Add version tag"
            tagType="version tag"
          />
        </div>
        <div className="w-[380px] shrink-0">
          <div className="comet-body-s-accented mb-2 flex items-center gap-1 text-foreground">
            Commit history
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_commit_history]}
            />
          </div>

          <CommitHistory
            versions={versions || []}
            activeVersionId={effectiveVersionId}
            onVersionClick={(version) => setActiveVersionId(version.id)}
            onRestoreVersionClick={handleRestoreVersionClick}
            latestVersionId={prompt.latest_version?.id}
          />
        </div>
      </div>
      <UseThisPromptDialog
        open={openUseThisPrompt}
        setOpen={setOpenUseThisPrompt}
        promptName={prompt.name}
        templateStructure={prompt.template_structure}
      />

      <EditPromptVersionDialog
        key={editPromptResetKeyRef.current}
        open={openEditPrompt}
        setOpen={handleOpenEditPrompt}
        promptName={prompt.name}
        template={activeVersion?.template || ""}
        metadata={activeVersion?.metadata}
        templateStructure={prompt.template_structure}
        type={activeVersion?.type}
        onSetActiveVersionId={setActiveVersionId}
      />

      <RestoreVersionDialog
        open={!!versionToRestore}
        setOpen={(v) => setVersionToRestore(v ? versionToRestore : null)}
        versionToRestore={versionToRestore}
        onSetActiveVersionId={setActiveVersionId}
      />
    </div>
  );
};

export default PromptTab;
