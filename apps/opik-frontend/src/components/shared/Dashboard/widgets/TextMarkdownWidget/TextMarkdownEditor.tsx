import React from "react";
import CodeMirror from "@uiw/react-codemirror";

import { AddWidgetConfig, TextMarkdownWidget } from "@/types/dashboard";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";

type TextMarkdownEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
};

const TextMarkdownEditor: React.FC<TextMarkdownEditorProps> = ({
  title,
  subtitle,
  config,
  onChange,
}) => {
  const codemirrorTheme = useCodemirrorTheme();
  const content = (config as TextMarkdownWidget["config"])?.content || "";

  const handleTitleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({ title: e.target.value });
  };

  const handleSubtitleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({ subtitle: e.target.value });
  };

  const handleContentChange = (value: string) => {
    onChange({
      config: {
        ...config,
        content: value,
      },
    });
  };

  return (
    <div className="flex h-full flex-col gap-4 overflow-auto p-4">
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="widget-title">Widget title</Label>
          <Input
            id="widget-title"
            placeholder="Enter widget title"
            value={title}
            onChange={handleTitleChange}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="widget-subtitle">Widget subtitle (optional)</Label>
          <Input
            id="widget-subtitle"
            placeholder="Enter widget subtitle"
            value={subtitle || ""}
            onChange={handleSubtitleChange}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="markdown-content">Markdown content</Label>
          <div className="overflow-hidden rounded-md border border-border">
            <CodeMirror
              value={content}
              onChange={handleContentChange}
              theme={codemirrorTheme}
              basicSetup={{
                lineNumbers: true,
                foldGutter: true,
                highlightActiveLineGutter: true,
                highlightActiveLine: true,
              }}
              minHeight="200px"
              maxHeight="300px"
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default TextMarkdownEditor;
