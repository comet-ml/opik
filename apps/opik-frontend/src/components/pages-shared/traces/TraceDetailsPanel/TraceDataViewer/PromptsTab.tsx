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
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

type RawPromptData = {
  id: string;
  name: string;
  version: {
    commit: string;
    id: string;
    template: string;
    metadata?: object;
  };
};

type OptimizerPromptPayload = {
  name?: string;
  type?: string;
  template?: Record<string, unknown>;
  rendered_messages?: unknown;
  opik_prompt?: RawPromptData;
  source_name?: string;
  system_prompt?: string;
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
    metadata: rawPrompt.version.metadata ?? {},
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
  const rawPrompts = get(
    data.metadata as Record<string, unknown>,
    "opik_prompts",
    null,
  ) as RawPromptData[] | null;
  const optimizerPayloads = get(
    data.metadata,
    "opik_optimizer.initial_prompts",
    null,
  ) as OptimizerPromptPayload[] | null;
  const spanPromptPayloads = get(
    data.metadata,
    "opik_optimizer.prompt_payloads",
    null,
  ) as OptimizerPromptPayload[] | null;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const showOptimizerPrompts = useIsFeatureEnabled(
    FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED,
  );

  const prompts = useMemo(() => {
    if (Array.isArray(rawPrompts) && rawPrompts.length > 0) {
      return (rawPrompts as RawPromptData[]).map(
        convertRawPromptToPromptWithLatestVersion,
      );
    }
    if (showOptimizerPrompts) {
      const mergedPayloads: OptimizerPromptPayload[] =
        Array.isArray(optimizerPayloads) && optimizerPayloads.length > 0
          ? optimizerPayloads
          : Array.isArray(spanPromptPayloads) && spanPromptPayloads.length > 0
            ? spanPromptPayloads
            : [];
      if (mergedPayloads.length > 0) {
        return mergedPayloads
          .map((payload, index) => {
            const baseName = payload?.name ?? "prompt";
            const label = payload?.source_name ?? `Candidate ${index + 1}`;
            const name = baseName === label ? label : `${label} · ${baseName}`;

            const templateFromPrompt = payload?.template;
            const templateMessages =
              payload?.type === "chat"
                ? Array.isArray(templateFromPrompt?.messages)
                  ? templateFromPrompt.messages
                  : [
                      ...(templateFromPrompt?.system
                        ? [
                            {
                              role: "system",
                              content: templateFromPrompt.system,
                            },
                          ]
                        : []),
                      ...(templateFromPrompt?.user
                        ? [{ role: "user", content: templateFromPrompt.user }]
                        : []),
                    ]
                : templateFromPrompt ?? payload?.rendered_messages ?? {};
            const templateString = JSON.stringify(templateMessages, null, 2);
            const rawPrompt: RawPromptData = {
              id: payload?.opik_prompt?.id || "",
              name,
              version: {
                commit:
                  payload?.opik_prompt?.version?.commit ||
                  `candidate-${index + 1}`,
                id: payload?.opik_prompt?.version?.id || "",
                template: templateString,
                metadata: { created_from: "opik_ui", type: "messages_json" },
              },
            };
            return convertRawPromptToPromptWithLatestVersion(rawPrompt);
          })
          .filter(Boolean);
      }
    }
    return [];
  }, [rawPrompts, optimizerPayloads, spanPromptPayloads, showOptimizerPrompts]);

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
