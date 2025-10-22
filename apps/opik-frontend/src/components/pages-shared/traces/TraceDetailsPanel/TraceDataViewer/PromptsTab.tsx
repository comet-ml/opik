import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import get from "lodash/get";
import { MessageSquareMore } from "lucide-react";

type PromptsTabProps = {
  data: Trace | Span;
  search?: string;
};

const PromptsTab: React.FunctionComponent<PromptsTabProps> = ({
  data,
  search,
}) => {
  const prompts = get(data.metadata, "opik_prompts", null);

  const hasPrompts = useMemo(() => {
    if (!prompts) return false;
    if (Array.isArray(prompts)) return (prompts as any[]).length > 0;
    return false; // opik_prompts should always be an array
  }, [prompts]);

  if (!hasPrompts) {
    return (
      <div className="flex h-32 items-center justify-center text-muted-slate">
        <div className="text-center">
          <p className="comet-body-s">No prompts found in metadata</p>
          <p className="comet-body-xs text-muted-slate">
            Prompts will appear here when they are included in the trace or span metadata
          </p>
        </div>
      </div>
    );
  }

  const renderPrompts = () => {
    if (!prompts || !Array.isArray(prompts)) return null;

    return (prompts as any[]).map((promptInfo: any, index: number) => {
      // Handle PromptInfoDict structure: {name, prompt, commit?}
      const promptName = promptInfo?.name || `Prompt ${index + 1}`;
      const promptContent = promptInfo?.prompt || promptInfo;
      const commitHash = promptInfo?.commit;

      return (
        <AccordionItem key={index} value={`prompt-${index}`}>
          <AccordionTrigger>
            <div className="flex items-center gap-2">
              <MessageSquareMore className="size-4 shrink-0 text-muted-slate" />
              <div className="flex flex-col items-start">
                <span>{promptName}</span>
                {commitHash && (
                  <span className="text-xs text-muted-slate font-mono">
                    {commitHash.substring(0, 8)}
                  </span>
                )}
              </div>
            </div>
          </AccordionTrigger>
          <AccordionContent>
            <div className="space-y-2">
              <SyntaxHighlighter withSearch data={promptContent} search={search} />
              {commitHash && (
                <div className="text-xs text-muted-slate border-t pt-2">
                  <strong>Commit:</strong> <code className="font-mono">{commitHash}</code>
                </div>
              )}
            </div>
          </AccordionContent>
        </AccordionItem>
      );
    });
  };

  return (
    <Accordion type="multiple" className="w-full" defaultValue={["prompt-0"]}>
      {renderPrompts()}
    </Accordion>
  );
};

export default PromptsTab;
