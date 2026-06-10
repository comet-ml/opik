import React, { useCallback, useEffect, useState } from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useToast } from "@/ui/use-toast";

type CodeBlockCopyProps = {
  text: string;
};

const CodeBlockCopy: React.FC<CodeBlockCopyProps> = ({ text }) => {
  const { toast } = useToast();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  const handleClick = useCallback(() => {
    copy(text);
    toast({ description: "Copied" });
    setCopied(true);
  }, [text, toast]);

  return (
    <TooltipWrapper content="Copy">
      <button
        type="button"
        onClick={handleClick}
        tabIndex={-1}
        className="flex size-3.5 shrink-0 items-center justify-center text-muted-slate transition-colors hover:text-foreground"
      >
        {copied ? (
          <Check className="size-2.5" />
        ) : (
          <Copy className="size-2.5" />
        )}
      </button>
    </TooltipWrapper>
  );
};

export default CodeBlockCopy;
