import React from "react";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { Description } from "@/ui/description";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";

interface TextPromptEditorProps {
  value: string;
  onChange: (value: string) => void;
  label?: string;
  labelClassName?: string;
  placeholder?: string;
  showDescription?: boolean;
  currentImages?: string[];
  currentVideos?: string[];
  currentAudios?: string[];
}

const TextPromptEditor: React.FC<TextPromptEditorProps> = ({
  value,
  onChange,
  label = "Prompt",
  labelClassName,
  placeholder = "Prompt",
  showDescription = true,
  currentImages = [],
  currentVideos = [],
  currentAudios = [],
}) => {
  return (
    <div className="flex flex-col gap-2 pb-4">
      <Label htmlFor="template" className={labelClassName}>
        {label}
      </Label>
      <Textarea
        id="template"
        className="comet-code"
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
      {showDescription && (
        <Description>
          {
            EXPLAINERS_MAP[EXPLAINER_ID.what_format_should_the_prompt_be]
              .description
          }
        </Description>
      )}
      {currentImages.length > 0 && (
        <div className="flex flex-col gap-2">
          <Label>Images</Label>
          <MediaTagsList
            type="image"
            items={currentImages}
            editable={false}
            preview={true}
          />
        </div>
      )}
      {currentVideos.length > 0 && (
        <div className="flex flex-col gap-2">
          <Label>Videos</Label>
          <MediaTagsList
            type="video"
            items={currentVideos}
            editable={false}
            preview={true}
          />
        </div>
      )}
      {currentAudios.length > 0 && (
        <div className="flex flex-col gap-2">
          <Label>Audios</Label>
          <MediaTagsList
            type="audio"
            items={currentAudios}
            editable={false}
            preview={true}
          />
        </div>
      )}
    </div>
  );
};

export default TextPromptEditor;
