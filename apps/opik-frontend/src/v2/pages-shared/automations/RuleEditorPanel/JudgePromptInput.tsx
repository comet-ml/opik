import React, { useRef } from "react";
import { Image, LucideIcon, Music, Video } from "lucide-react";

import { Button } from "@/ui/button";
import { FormControl } from "@/ui/form";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { MessageContent } from "@/types/llm";
import { useMessageContent } from "@/hooks/useMessageContent";
import { Tag } from "@/ui/tag";
import PromptVariableEditor, {
  PromptVariableEditorHandle,
} from "@/v2/pages-shared/llm/PromptVariableEditor/PromptVariableEditor";
import AddMediaPopover, {
  MediaType,
} from "@/v2/pages-shared/llm/PromptMessageMediaTags/AddMediaPopover";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import { RuleSampleData } from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleSampleData";

const MEDIA_BUTTONS: { type: MediaType; label: string; icon: LucideIcon }[] = [
  { type: "image", label: "Add image", icon: Image },
  { type: "audio", label: "Add audio", icon: Music },
  { type: "video", label: "Add video", icon: Video },
];

const MEDIA_NOTE = (
  <>
    We recommend using{" "}
    <Tag variant="green" className="px-1 align-top font-mono">
      {"{{trace}}"}
    </Tag>{" "}
    in the prompt for multi-modal agent evaluation.
  </>
);

type JudgePromptInputProps = {
  value: MessageContent;
  onChange: (value: MessageContent) => void;
  onBlur: () => void;
  sampleData: RuleSampleData | null;
  showMedia: boolean;
  editorRef?: React.RefObject<PromptVariableEditorHandle>;
};

const JudgePromptInput: React.FC<JudgePromptInputProps> = ({
  value,
  onChange,
  onBlur,
  sampleData,
  showMedia,
  editorRef: externalEditorRef,
}) => {
  const localEditorRef = useRef<PromptVariableEditorHandle>(null);
  const editorRef = externalEditorRef ?? localEditorRef;
  const {
    localText,
    images,
    videos,
    audios,
    setImages,
    setVideos,
    setAudios,
    handleContentChange,
  } = useMessageContent({ content: value, onChangeContent: onChange });

  const media = {
    image: { items: images, setItems: setImages },
    audio: { items: audios, setItems: setAudios },
    video: { items: videos, setItems: setVideos },
  };
  const hasMedia = images.length > 0 || videos.length > 0 || audios.length > 0;

  return (
    <>
      <FormControl>
        <div
          className="min-h-80 flex-1 cursor-text px-3 py-2"
          onClick={() => editorRef.current?.focus()}
        >
          <PromptVariableEditor
            ref={editorRef}
            value={localText}
            onChange={handleContentChange}
            onBlur={onBlur}
            placeholder="Type your prompt..."
            jsonTreeData={sampleData?.treeData}
            jsonTreeEmptyState={sampleData?.emptyState}
            openInsideTag
          />
        </div>
      </FormControl>
      {showMedia && (
        <div className="flex flex-col gap-1 px-2 pb-1">
          {hasMedia && (
            <div className="flex flex-wrap items-center gap-1.5 px-1">
              {MEDIA_BUTTONS.map(({ type }) => (
                <MediaTagsList
                  key={type}
                  type={type}
                  items={media[type].items}
                  setItems={media[type].setItems}
                />
              ))}
            </div>
          )}
          <div className="flex min-h-8 items-center">
            {MEDIA_BUTTONS.map(({ type, label, icon: Icon }) => (
              <AddMediaPopover
                key={type}
                type={type}
                items={media[type].items}
                setItems={media[type].setItems}
                note={MEDIA_NOTE}
              >
                <TooltipWrapper content={label}>
                  <Button
                    variant="minimal"
                    size="icon-2xs"
                    type="button"
                    aria-label={label}
                  >
                    <Icon />
                  </Button>
                </TooltipWrapper>
              </AddMediaPopover>
            ))}
          </div>
        </div>
      )}
    </>
  );
};

export default JudgePromptInput;
