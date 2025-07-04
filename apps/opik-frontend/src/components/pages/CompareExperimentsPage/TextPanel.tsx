import React from "react";
import { TextPanelConfig } from "./dashboardTypes";

interface TextPanelProps {
  config: TextPanelConfig;
  id: string;
}

const TextPanel: React.FC<TextPanelProps> = ({ config, id }) => {
  const { content, format } = config;

  const renderContent = () => {
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
          <div className="whitespace-pre-wrap text-sm">
            {content}
          </div>
        );
    }
  };

  return (
    <div className="h-full p-4 overflow-auto bg-background">
      {renderContent()}
      {format === "markdown" && (
        <div className="mt-4 p-3 bg-accent/50 rounded-md border">
          <p className="comet-body-xs text-muted-foreground">
            Full markdown rendering will be implemented with a markdown parser
          </p>
        </div>
      )}
    </div>
  );
};

export default TextPanel; 