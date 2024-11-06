import React, { useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { Info, Pencil } from "lucide-react";
import { PromptWithLatestVersion } from "@/types/prompts";
import Loader from "@/components/shared/Loader/Loader";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import UseThisPromptDialog from "@/components/pages/PromptPage/PromptTab/UseThisPromptDialog";
import EditPromptDialog from "@/components/pages/PromptPage/PromptTab/EditPromptDialog";
import CommitHistory from "@/components/pages/PromptPage/PromptTab/CommitHistory";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { StringParam, useQueryParam } from "use-query-params";

interface PromptTabInterface {
  prompt?: PromptWithLatestVersion;
}

const PromptTab = ({ prompt }: PromptTabInterface) => {
  const [openUseThisPrompt, setOpenUseThisPrompt] = useState(false);
  const [openEditPrompt, setOpenEditPrompt] = useState(false);

  const [activeVersionId, setActiveVersionId] = useQueryParam(
    "activeVersionId",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const editPromptResetKeyRef = useRef(0);

  const { data } = usePromptVersionsById(
    {
      promptId: prompt?.id || "",
      page: 1,
      size: 25,
    },
    {
      enabled: !!prompt?.id,
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
    editPromptResetKeyRef.current += 1;
    setOpenEditPrompt(value);
  };

  useEffect(() => {
    if (prompt?.latest_version?.id && !activeVersionId) {
      setActiveVersionId(prompt.latest_version.id);
    }
  }, [prompt?.latest_version?.id, activeVersionId, setActiveVersionId]);

  useEffect(() => {
    return () => {
      setActiveVersionId(null);
    };
  }, [setActiveVersionId]);

  if (!prompt) {
    return <Loader />;
  }

  return (
    <>
      <div>
        <div className="flex items-center justify-between w-full">
          <Button variant="outline" onClick={() => setOpenUseThisPrompt(true)}>
            <Info className="mr-2 size-4" />
            Use this prompt
          </Button>

          <Button variant="secondary" onClick={() => setOpenEditPrompt(true)}>
            <Pencil className="mr-2 size-4" />
            Edit prompt
          </Button>
        </div>

        <div className="flex items-stretch gap-2 p-6 mt-6 border rounded-md bg-white">
          <div className="flex flex-col grow">
            <p className="comet-body-s-accented text-foreground">Prompt</p>
            <code className="flex mt-2 p-3 rounded-md break-words whitespace-pre-wrap size-full comet-code bg-[#FBFCFD]">
              {activeVersion?.template}
            </code>
          </div>
          <div className="w-[320px]">
            <p className="mb-2 comet-body-s-accented text-foreground">
              Commit history
            </p>

            <CommitHistory
              versions={versions || []}
              activeVersionId={activeVersionId || ""}
              onVersionClick={(version) => setActiveVersionId(version.id)}
            />
          </div>
        </div>
      </div>

      <UseThisPromptDialog
        open={openUseThisPrompt}
        setOpen={setOpenUseThisPrompt}
      />

      <EditPromptDialog
        key={editPromptResetKeyRef.current}
        open={openEditPrompt}
        setOpen={handleOpenEditPrompt}
        promptName={prompt.name}
        promptTemplate={prompt.latest_version?.template || ""}
        onSetActiveVersionId={setActiveVersionId}
      />
    </>
  );
};

export default PromptTab;
