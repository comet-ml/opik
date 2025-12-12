import React, { useEffect, useRef, useState, useMemo } from "react";
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
import MediaTagsList from "@/components/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import { parseLLMMessageContent, parsePromptVersionContent } from "@/lib/llm";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import ChatPromptView from "./ChatPromptView";
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
    },
    {
      enabled: !!prompt?.id,
      refetchInterval: 30000,
    },
  );

  const { data: activeVersion } = usePromptVersionById(
    {
      versionId: activeVersionId || "",
    },
    {
      enabled: !!activeVersionId,
    },
  );

  const versions = data?.content;

  const handleOpenEditPrompt = (value: boolean) => {
    editPromptResetKeyRef.current = editPromptResetKeyRef.current + 1;
    setOpenEditPrompt(value);
  };

  const handleRestoreVersionClick = (version: PromptVersion) => {
    setVersionToRestore(version);
  };

  useEffect(() => {
    if (versions && versions.length > 0 && !activeVersionId) {
      setActiveVersionId(versions[0].id, "replaceIn");
    }
  }, [versions, activeVersionId, setActiveVersionId]);

  useEffect(() => {
    return () => {
      setActiveVersionId(null);
    };
  }, [setActiveVersionId]);

  const displayText = activeVersion?.template || "";

  const {
    images: extractedImages,
    videos: extractedVideos,
    audios: extractedAudios,
  } = useMemo(() => {
    const content = parsePromptVersionContent(activeVersion);
    return parseLLMMessageContent(content);
  }, [activeVersion]);

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
          <div className="flex items-center gap-2">
            <p className="comet-body-s-accented text-foreground">
              {isChatPrompt ? "Chat messages" : "Prompt"}
            </p>
            {!isChatPrompt && (
              <CopyButton
                text={displayText}
                message="Prompt copied to clipboard"
                tooltipText="Copy prompt"
                variant="ghost"
                size="icon-xs"
              />
            )}
          </div>
          {isChatPrompt ? (
            <ChatPromptView template={activeVersion?.template || ""} />
          ) : (
            <>
              <code className="comet-code flex w-full whitespace-pre-wrap break-all rounded-md bg-primary-foreground p-3">
                {displayText}
              </code>
              {extractedImages.length > 0 && (
                <>
                  <p className="comet-body-s-accented mt-4 text-foreground">
                    Images
                  </p>
                  <MediaTagsList
                    type="image"
                    items={extractedImages}
                    editable={false}
                    preview={true}
                  />
                </>
              )}
              {extractedVideos.length > 0 && (
                <>
                  <p className="comet-body-s-accented mt-4 text-foreground">
                    Videos
                  </p>
                  <MediaTagsList
                    type="video"
                    items={extractedVideos}
                    editable={false}
                    preview={true}
                  />
                </>
              )}
              {extractedAudios.length > 0 && (
                <>
                  <p className="comet-body-s-accented mt-4 text-foreground">
                    Audios
                  </p>
                  <MediaTagsList
                    type="audio"
                    items={extractedAudios}
                    editable={false}
                    preview={true}
                  />
                </>
              )}
            </>
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

          <>
            <p className="comet-body-s-accented mt-4 text-foreground">Tags</p>
            <TagListRenderer
              tags={activeVersion?.tags || []}
              onAddTag={(newTag) => {
                if (!activeVersion?.id) return;
                const updatedTags = [...(activeVersion.tags || []), newTag];
                updateVersionsMutation.mutate({
                  versionIds: [activeVersion.id],
                  tags: updatedTags,
                  mergeTags: false,
                });
              }}
              onDeleteTag={(tagToDelete) => {
                if (!activeVersion?.id) return;
                const updatedTags = (activeVersion.tags || []).filter(
                  (t) => t !== tagToDelete,
                );
                updateVersionsMutation.mutate({
                  versionIds: [activeVersion.id],
                  tags: updatedTags,
                  mergeTags: false,
                });
              }}
              align="start"
            />
          </>

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
            activeVersionId={activeVersionId || ""}
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
