import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkBreaks from "remark-breaks";
import isNull from "lodash/isNull";

import { cn, isStringMarkdown } from "@/lib/utils";
import { makeHeadingsCollapsible } from "@/lib/remarkCollapsibleHeadings";

type MarkdownPreviewProps = {
  children?: string | null;
  className?: string;
};

export const MarkdownPreview: React.FC<MarkdownPreviewProps> = ({
  children,
  className,
}) => {
  if (!children) return "";

  if (isStringMarkdown(children)) {
    // Transform the markdown to make headings collapsible
    const collapsibleMarkdown = makeHeadingsCollapsible(children, {
      defaultOpen: false,
      className: "collapsible-heading",
      summaryClassName: "collapsible-heading-summary",
      contentClassName: "collapsible-heading-content",
    });

    return (
      <ReactMarkdown
        className={cn("prose dark:prose-invert comet-markdown", className)}
        remarkPlugins={[remarkBreaks, remarkGfm]}
      >
        {collapsibleMarkdown}
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
