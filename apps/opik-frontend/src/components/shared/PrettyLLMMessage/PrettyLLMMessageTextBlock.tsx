import React from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useHeightTruncation } from "@/hooks/useHeightTruncation";
import { PrettyLLMMessageTextBlockProps } from "./types";
import { MarkdownPreview } from "@/components/shared/MarkdownPreview/MarkdownPreview";

const MAX_LINES = 3;

const PrettyLLMMessageTextBlock: React.FC<PrettyLLMMessageTextBlockProps> =
  React.memo(({ children, role, showMoreButton = true, className }) => {
    const shouldTruncate = role === "system";
    const { ref, isTruncated, isExpanded, toggle } = useHeightTruncation(
      MAX_LINES,
      shouldTruncate,
    );

    const showButton = showMoreButton && shouldTruncate && isTruncated;

    return (
      <div className={cn("space-y-2", className)}>
        <div
          ref={ref}
          className={cn(
            "text-sm text-foreground",
            shouldTruncate && !isExpanded && "line-clamp-3",
          )}
        >
          {typeof children === "string" ? (
            <MarkdownPreview>{children}</MarkdownPreview>
          ) : (
            children
          )}
        </div>
        {showButton && (
          <Button
            variant="tableLink"
            size="sm"
            onClick={toggle}
            className="h-auto p-0 text-xs"
          >
            {isExpanded ? "Show less" : "Show more"}
          </Button>
        )}
      </div>
    );
  });

PrettyLLMMessageTextBlock.displayName = "PrettyLLMMessageTextBlock";

export default PrettyLLMMessageTextBlock;
