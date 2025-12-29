import React, { useState } from "react";
import { Eye, FileText } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Description } from "@/components/ui/description";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import MediaTagsList from "@/components/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface TextPromptEditorProps {
  value: string;
  onChange: (value: string) => void;
  label?: string;
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
  placeholder = "Prompt",
  showDescription = true,
  currentImages = [],
  currentVideos = [],
  currentAudios = [],
}) => {
  const [showPrettyView, setShowPrettyView] = useState(false);

  return (
    <div className="flex flex-col gap-2 pb-4">
      <div className="flex items-center justify-between gap-0.5">
        <Label htmlFor="template">{label}</Label>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setShowPrettyView(!showPrettyView)}
        >
          {showPrettyView ? (
            <>
              <FileText className="mr-1.5 size-3.5" />
              Edit view
            </>
          ) : (
            <>
              <Eye className="mr-1.5 size-3.5" />
              Pretty view
            </>
          )}
        </Button>
      </div>
      {showPrettyView ? (
        <div className="min-h-44 rounded-md border border-border bg-primary-foreground p-3">
          <MarkdownPreview>{value}</MarkdownPreview>
        </div>
      ) : (
        <>
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
        </>
      )}
      {!showPrettyView && currentImages.length > 0 && (
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
      {!showPrettyView && currentVideos.length > 0 && (
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
      {!showPrettyView && currentAudios.length > 0 && (
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
