import React, { useMemo, useState } from "react";
import { Span, Trace } from "@/types/traces";
import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import { PromptLibraryMetadata } from "@/types/playground";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import PromptMessagesReadonly from "@/components/pages-shared/llm/PromptMessagesReadonly/PromptMessagesReadonly";
import get from "lodash/get";
import {
  Code2,
  ExternalLink,
  FileTerminal,
  GitCommitVertical,
  MessageSquare,
} from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import TryInPlaygroundButton from "@/components/pages/PromptPage/TryInPlaygroundButton";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

// Helper to ensure template is always a string for PromptVersion
// The template from trace metadata can be either a string (legacy) or parsed JSON object (new format)
const normalizeTemplate = (template: unknown): string => {
  if (typeof template === "string") {
    return template;
  }
  if (template !== null && template !== undefined) {
    return JSON.stringify(template, null, 2);
  }
  return "";
};

type PromptsTabProps = {
  data: Trace | Span;
  search?: string;
};

const convertRawPromptToPromptWithLatestVersion = (
  rawPrompt: PromptLibraryMetadata,
): PromptWithLatestVersion => {
  const date = new Date().toISOString();

  // Use existing metadata or create default metadata for messages_json format
  // This ensures parsePromptVersionContent knows how to parse the template correctly
  const metadata = rawPrompt.version.metadata ?? {
    created_from: "opik_ui",
    type: "messages_json",
  };

  const promptVersion: PromptVersion = {
    id: rawPrompt.version.id,
    template: normalizeTemplate(rawPrompt.version.template),
    metadata,
    commit: rawPrompt.version.commit ?? "",
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

interface PromptContentViewProps {
  template: unknown;
  promptInfo: PromptWithLatestVersion;
  promptId?: string;
  workspaceName: string;
  search?: string;
}

const PromptContentView: React.FC<PromptContentViewProps> = ({
  template,
  promptInfo,
  promptId,
  workspaceName,
  search,
}) => {
  const [showRawView, setShowRawView] = useState(false);

  const templateString = useMemo(() => {
    if (typeof template === "string") return template;
    if (template !== null && template !== undefined) {
      return JSON.stringify(template, null, 2);
    }
    return "";
  }, [template]);

  const hasMessages = useMemo(() => {
    try {
      const data =
        typeof template === "string" ? JSON.parse(template) : template;
      return Array.isArray(data) && data.length > 0;
    } catch {
      return false;
    }
  }, [template]);

  return (
    <div className="space-y-2">
      {hasMessages && (
        <div className="flex items-center justify-end">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setShowRawView(!showRawView)}
          >
            {showRawView ? (
              <>
                <MessageSquare className="mr-1.5 size-3.5" />
                Message view
              </>
            ) : (
              <>
                <Code2 className="mr-1.5 size-3.5" />
                Raw view
              </>
            )}
          </Button>
        </div>
      )}

      {showRawView || !hasMessages ? (
        <SyntaxHighlighter
          withSearch
          data={template as object}
          search={search}
        />
      ) : (
        <PromptMessagesReadonly template={templateString} />
      )}

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
  );
};

const PromptsTab: React.FunctionComponent<PromptsTabProps> = ({
  data,
  search,
}) => {
  const rawPrompts = get(
    data.metadata as Record<string, unknown>,
    "opik_prompts",
    null,
  ) as PromptLibraryMetadata[] | null;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const showOptimizerPrompts = useIsFeatureEnabled(
    FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED,
  );

  const prompts = useMemo(() => {
    if (!showOptimizerPrompts) return [];
    if (Array.isArray(rawPrompts) && rawPrompts.length > 0) {
      return (rawPrompts as PromptLibraryMetadata[]).map(
        convertRawPromptToPromptWithLatestVersion,
      );
    }
    return [];
  }, [rawPrompts, showOptimizerPrompts]);

  const renderPrompts = () => {
    if (!prompts || prompts.length === 0 || !rawPrompts) return null;

    return prompts.map((promptInfo: PromptWithLatestVersion, index: number) => {
      const promptName = promptInfo?.name || `Prompt ${index + 1}`;
      const rawTemplate = rawPrompts[index]?.version?.template;
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
            <PromptContentView
              template={rawTemplate}
              promptInfo={promptInfo}
              promptId={promptId}
              workspaceName={workspaceName}
              search={search}
            />
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
