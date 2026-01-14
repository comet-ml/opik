import React, { useState, useRef } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { PrettyLLMMessageTextBlockProps } from "./types";

const PrettyLLMMessageTextBlock: React.FC<PrettyLLMMessageTextBlockProps> = ({
  children,
  showMoreButton = true,
  className,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [needsTruncation, setNeedsTruncation] = useState(false);
  const textRef = useRef<HTMLDivElement>(null);

  const checkTruncation = (element: HTMLDivElement) => {
    const lineHeight = parseInt(
      window.getComputedStyle(element).lineHeight || "20",
      10,
    );
    const maxHeight = lineHeight * 3;
    setNeedsTruncation(element.scrollHeight > maxHeight);
  };

  const handleRef = (node: HTMLDivElement | null) => {
    textRef.current = node;
    if (node && !isExpanded) {
      checkTruncation(node);
    } else {
      setNeedsTruncation(false);
    }
  };

  const handleToggle = () => {
    const newExpanded = !isExpanded;
    setIsExpanded(newExpanded);

    if (!newExpanded && textRef.current) {
      // Re-check truncation after collapsing
      requestAnimationFrame(() => {
        if (textRef.current) {
          checkTruncation(textRef.current);
        }
      });
    } else {
      setNeedsTruncation(false);
    }
  };

  const shouldShowButton = showMoreButton && needsTruncation;

  return (
    <div className={cn("space-y-2", className)}>
      <div
        ref={handleRef}
        className={cn("text-sm text-foreground", !isExpanded && "line-clamp-3")}
      >
        {typeof children === "string" ? (
          <span className="whitespace-pre-wrap">{children}</span>
        ) : (
          children
        )}
      </div>
      {shouldShowButton && (
        <Button
          variant="ghost"
          size="sm"
          onClick={handleToggle}
          className="h-auto p-0 text-xs text-primary hover:text-primary/80"
        >
          {isExpanded ? "Show less" : "Show more"}
        </Button>
      )}
    </div>
  );
};

export default PrettyLLMMessageTextBlock;
