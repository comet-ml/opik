import React, { useMemo } from "react";
import { TextPanelConfig } from "./dashboardTypes";

interface TextPanelProps {
  config: TextPanelConfig;
  id: string;
}

const TextPanel: React.FC<TextPanelProps> = ({ config, id }) => {
  const { content, format } = config;

  // Memoize the rendered content based on format
  const renderedContent = useMemo(() => {
    switch (format) {
      case "html":
        return (
          <div 
            className="prose prose-sm max-w-none"
            dangerouslySetInnerHTML={{ __html: content }}
          />
        );
      case "markdown":
        // For now, render as plain text with basic markdown styling
        // In a real implementation, you'd use a markdown parser like react-markdown
        return (
          <div className="prose prose-sm max-w-none whitespace-pre-wrap">
            {content}
          </div>
        );
      case "plain":
      default:
        return (
          <div className="comet-body-s whitespace-pre-wrap">
            {content}
          </div>
        );
    }
  }, [content, format]);

  // Memoize the markdown notice
  const markdownNotice = useMemo(() => {
    if (format !== "markdown") return null;
    
    return (
      <div className="mt-4 p-3 bg-accent/50 rounded-md border">
        <p className="comet-body-small text-muted-slate">
          Full markdown rendering will be implemented with a markdown parser
        </p>
      </div>
    );
  }, [format]);

  return (
    <div className="h-full p-4 overflow-auto bg-background">
      {renderedContent}
      {markdownNotice}
    </div>
  );
};

export default React.memo(TextPanel); 
