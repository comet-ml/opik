import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { cn } from "@/lib/utils";

type MarkdownPreviewProps = {
  children?: string | null;
  className?: string;
};

export const MarkdownPreview: React.FC<MarkdownPreviewProps> = ({
  children,
  className,
}) => {
  return (
    <ReactMarkdown
      className={cn("prose comet-markdown", className)}
      remarkPlugins={[remarkGfm]}
    >
      {children}
    </ReactMarkdown>
  );
};

export default MarkdownPreview;
