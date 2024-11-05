import React, { useMemo, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { Info, Pencil } from "lucide-react";
import { PromptWithLatestVersion } from "@/types/prompts";
import Loader from "@/components/shared/Loader/Loader";
import usePromptVersionsById from "@/api/prompts/usePromptsVersionsById";
import UseThisPromptDialog from "@/components/pages/PromptPage/PromptTab/UseThisPromptDialog";
import EditPromptDialog from "@/components/pages/PromptPage/PromptTab/EditPromptDialog";
import CommitHistory from "@/components/pages/PromptPage/PromptTab/CommitHistory";

interface PromptTabInterface {
  prompt?: PromptWithLatestVersion;
}

const PromptTab = ({ prompt }: PromptTabInterface) => {
  const [openUseThisPrompt, setOpenUseThisPrompt] = useState(false);
  const [openEditPrompt, setOpenEditPrompt] = useState(false);
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

  const versions = data?.content;

  const handleOpenEditPrompt = (value: boolean) => {
    editPromptResetKeyRef.current += 1;
    setOpenEditPrompt(value);
  };

  if (!prompt) {
    return <Loader />;
  }

  return (
    <>
      <div>
        <div className="flex justify-between items-center w-full">
          <Button variant="outline" onClick={() => setOpenUseThisPrompt(true)}>
            <Info className="mr-2 size-4" />
            Use this prompt
          </Button>

          <Button variant="secondary" onClick={() => setOpenEditPrompt(true)}>
            <Pencil className="mr-2 size-4" />
            Edit prompt
          </Button>
        </div>

        <div className="flex items-stretch rounded-md border bg-white px-6 pb-6 pt-6 mt-6 gap-2">
          <div className="flex flex-col flex-grow">
            <p className="comet-body-s-accented text-foreground">Prompt</p>
            <code className="comet-code w-full break-words whitespace-pre-wrap rounded-md bg-[#FBFCFD] p-3 flex mt-2 h-full">
              {prompt?.latest_version?.template}
            </code>
          </div>
          <div className="w-[320px]">
            <p className="comet-body-s-accented text-foreground mb-2">
              Commit history
            </p>

            <CommitHistory versions={versions || []} />
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
        promptId={prompt.id}
        promptTemplate={prompt.latest_version?.template || ""}
      />
    </>
  );
};

export default PromptTab;
