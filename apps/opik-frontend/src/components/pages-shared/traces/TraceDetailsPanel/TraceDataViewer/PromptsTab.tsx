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
import { MessageSquareMore, ExternalLink } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type PromptInfoDict = {
  name: string;
  prompt: string;
  commit?: string;
  prompt_id?: string;
  version_id?: string;
};

type PromptsTabProps = {
  data: Trace | Span;
  search?: string;
};

const PromptsTab: React.FunctionComponent<PromptsTabProps> = ({
  data,
  search,
}) => {
  const prompts = get(data.metadata, "opik_prompts", null);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const hasPrompts = useMemo(() => {
    if (!prompts) return false;
    if (Array.isArray(prompts)) return (prompts as PromptInfoDict[]).length > 0;
    return false; // opik_prompts should always be an array
  }, [prompts]);

  if (!hasPrompts) {
    return (
      <div className="flex h-32 items-center justify-center text-muted-slate">
        <div className="text-center">
          <p className="comet-body-s">No prompts found in metadata</p>
          <p className="comet-body-xs text-muted-slate">
            Prompts will appear here when they are included in the trace or span
            metadata
          </p>
        </div>
      </div>
    );
  }

  const renderPrompts = () => {
    if (!prompts || !Array.isArray(prompts)) return null;

    return (prompts as PromptInfoDict[]).map(
      (promptInfo: PromptInfoDict, index: number) => {
        // Handle PromptInfoDict structure: {name, prompt, commit?, prompt_id?}
        const promptName = promptInfo?.name || `Prompt ${index + 1}`;
        const promptContent = promptInfo?.prompt || promptInfo;
        const commitHash = promptInfo?.commit;
        const promptId = promptInfo?.prompt_id;

        return (
          <AccordionItem key={index} value={`prompt-${index}`}>
            <AccordionTrigger>
              <div className="flex items-center gap-2">
                <MessageSquareMore className="size-4 shrink-0 text-muted-slate" />
                <div className="flex flex-col items-start">
                  <div className="flex items-center gap-2">
                    <span>{promptName}</span>
                    {promptId && (
                      <Link
                        to="/$workspaceName/prompts/$promptId"
                        params={{ workspaceName, promptId }}
                        className="text-blue-600 transition-colors hover:text-blue-800"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <ExternalLink className="size-3" />
                      </Link>
                    )}
                  </div>
                  {commitHash && (
                    <span className="font-mono text-xs text-muted-slate">
                      {commitHash.substring(0, 8)}
                    </span>
                  )}
                </div>
              </div>
            </AccordionTrigger>
            <AccordionContent>
              <div className="space-y-2">
                <SyntaxHighlighter
                  withSearch
                  data={promptContent as object}
                  search={search}
                />
                <div className="flex flex-col gap-2 border-t pt-2 text-xs text-muted-slate">
                  {commitHash && (
                    <div>
                      <strong>Commit:</strong>{" "}
                      <code className="font-mono">{commitHash}</code>
                    </div>
                  )}
                  {promptId && (
                    <div>
                      <Link
                        to="/$workspaceName/prompts/$promptId"
                        params={{ workspaceName, promptId }}
                        className="inline-flex items-center gap-1 text-blue-600 transition-colors hover:text-blue-800"
                      >
                        <ExternalLink className="size-3" />
                        View in Prompt Library
                      </Link>
                    </div>
                  )}
                </div>
              </div>
            </AccordionContent>
          </AccordionItem>
        );
      },
    );
  };

  return (
    <Accordion type="multiple" className="w-full" defaultValue={["prompt-0"]}>
      {renderPrompts()}
    </Accordion>
  );
};

export default PromptsTab;
