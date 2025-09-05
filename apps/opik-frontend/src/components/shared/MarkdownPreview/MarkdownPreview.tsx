import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkBreaks from "remark-breaks";
import isNull from "lodash/isNull";

import { cn, isStringMarkdown } from "@/lib/utils";

type MarkdownPreviewProps = {
  children?: string | null;
  className?: string;
};

export const MarkdownPreview: React.FC<MarkdownPreviewProps> = ({
  children,
  className,
}) => {
  if (isNull(children)) return "";

  if (isStringMarkdown(children)) {
    return (
      <ReactMarkdown
        className={cn("prose dark:prose-invert comet-markdown", className)}
        remarkPlugins={[remarkBreaks, remarkGfm]}
      >
        {children}
      </ReactMarkdown>
    );
  } else {
    return (
      <div className={cn("comet-markdown whitespace-pre-wrap", className)}>
        {children}
      </div>
    );
  }
};

export default MarkdownPreview;
