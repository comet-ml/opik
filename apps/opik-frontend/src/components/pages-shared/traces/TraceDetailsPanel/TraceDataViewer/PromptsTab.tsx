import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import get from "lodash/get";
import { ExternalLink, FileTerminal, GitCommitVertical } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import TryInPlaygroundButton from "@/components/pages/PromptPage/TryInPlaygroundButton";

type RawPromptData = {
  id: string;
  name: string;
  version: {
    commit: string;
    id: string;
    template: string;
  };
};

type PromptsTabProps = {
  data: Trace | Span;
  search?: string;
};

const convertRawPromptToPromptWithLatestVersion = (
  rawPrompt: RawPromptData,
): PromptWithLatestVersion => {
  const date = new Date().toISOString();

  const promptVersion: PromptVersion = {
    id: rawPrompt.version.id,
    template: rawPrompt.version.template,
    metadata: {},
    commit: rawPrompt.version.commit,
    prompt_id: rawPrompt.id,
    created_at: date, // We don't have this in raw data, using current time
  };

  return {
    id: rawPrompt.id,
    name: rawPrompt.name,
    description: "", // We don't have this in raw data
    last_updated_at: date, // We don't have this in raw data
    created_at: date, // We don't have this in raw data
    version_count: 1, // Assuming single version
    tags: [], // We don't have this in raw data
    latest_version: promptVersion,
  };
};

// Custom Button component that matches the "Use in Playground" styling
const CustomUseInPlaygroundButton: React.FC<{
  variant?: string;
  size?: string;
  disabled?: boolean;
  onClick?: () => void;
  children: React.ReactNode;
  className?: string;
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
}> = ({ onClick, disabled, size, variant, ...props }) => {
  return (
    <Button
      variant="ghost"
      onClick={onClick}
      size="sm"
      disabled={disabled}
      className="inline-flex items-center gap-1"
      {...props}
    >
      Use in Playground
      <ExternalLink className="size-3.5 shrink-0" />
    </Button>
  );
};

const PromptsTab: React.FunctionComponent<PromptsTabProps> = ({
  data,
  search,
}) => {
  const rawPrompts = get(data.metadata, "opik_prompts", null);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const prompts = useMemo(() => {
    if (!rawPrompts || !Array.isArray(rawPrompts)) return [];
    return (rawPrompts as RawPromptData[]).map(
      convertRawPromptToPromptWithLatestVersion,
    );
  }, [rawPrompts]);

  const renderPrompts = () => {
    if (!prompts || prompts.length === 0) return null;

    return prompts.map((promptInfo: PromptWithLatestVersion, index: number) => {
      const promptName = promptInfo?.name || `Prompt ${index + 1}`;
      const promptContent = promptInfo?.latest_version?.template || promptInfo;
      const commitHash = promptInfo?.latest_version?.commit;
      const promptId = promptInfo?.id;

      return (
        <AccordionItem key={index} value={`prompt-${index}`}>
          <AccordionTrigger>
            <div className="flex items-center gap-2">
              <FileTerminal className="size-4" />
              <div className="flex flex-col items-start">
                <div className="flex items-center gap-2">
                  <span>Prompt: {promptName}</span>
                  {commitHash && (
                    <div className="flex items-center">
                      <GitCommitVertical className="size-3 text-muted-slate" />
                      <span className="text-muted-slate">
                        {commitHash.substring(0, 8)}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </AccordionTrigger>
          <AccordionContent>
            <div className="space-x-1 space-y-2">
              <SyntaxHighlighter
                withSearch
                data={promptContent as object}
                search={search}
              />
              <div className="flex items-center justify-between text-xs text-muted-slate">
                {promptId && (
                  <Button variant="outline" size="sm" asChild>
                    <Link
                      to="/$workspaceName/prompts/$promptId"
                      params={{ workspaceName, promptId }}
                      className="inline-flex items-center"
                    >
                      View in Prompt library
                    </Link>
                  </Button>
                )}
                <TryInPlaygroundButton
                  prompt={promptInfo}
                  ButtonComponent={CustomUseInPlaygroundButton}
                />
              </div>
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
