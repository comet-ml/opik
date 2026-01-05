import React, { useState } from "react";
import { Code2, Type } from "lucide-react";
import { Button } from "@/components/ui/button";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import MediaTagsList from "@/components/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

interface TextPromptViewProps {
  template: string;
  extractedImages: string[];
  extractedVideos: string[];
  extractedAudios: string[];
}

const TextPromptView: React.FC<TextPromptViewProps> = ({
  template,
  extractedImages,
  extractedVideos,
  extractedAudios,
}) => {
  const [showRawView, setShowRawView] = useState(false);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="text-sm text-muted-foreground">
          {showRawView ? "Raw text" : "Text prompt"}
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setShowRawView(!showRawView)}
        >
          {showRawView ? (
            <>
              <Type className="mr-1.5 size-3.5" />
              Pretty view
            </>
          ) : (
            <>
              <Code2 className="mr-1.5 size-3.5" />
              Raw view
            </>
          )}
        </Button>
      </div>
      {showRawView ? (
        <div className="max-h-[600px] overflow-y-auto">
          <CodeHighlighter data={template} />
        </div>
      ) : (
        <>
          <div
            className="rounded-md bg-primary-foreground p-3"
            data-testid="prompt-text-content"
          >
            <MarkdownPreview>{template}</MarkdownPreview>
          </div>
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
    </div>
  );
};

export default TextPromptView;
