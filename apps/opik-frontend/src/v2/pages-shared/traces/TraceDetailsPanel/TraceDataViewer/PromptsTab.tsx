import React from "react";
import get from "lodash/get";

import { Span, Trace } from "@/types/traces";
import { PromptLibraryMetadata } from "@/types/playground";
import useAppStore from "@/store/AppStore";
import PromptCard from "./PromptCard";

type PromptsTabProps = {
  data: Trace | Span;
  search?: string;
};

export const getRawPrompts = (
  data: Trace | Span,
): PromptLibraryMetadata[] | null => {
  const value = get(
    data.metadata as Record<string, unknown> | undefined,
    "opik_prompts",
    null,
  );
  if (!Array.isArray(value) || value.length === 0) return null;
  return value as PromptLibraryMetadata[];
};

const PromptsTab: React.FunctionComponent<PromptsTabProps> = ({
  data,
  search,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const rawPrompts = getRawPrompts(data);

  if (!rawPrompts) return null;

  return (
    <div className="flex flex-col gap-3 py-2">
      {rawPrompts.map((rawPrompt, idx) => (
        <PromptCard
          key={`${rawPrompt.id}-${rawPrompt.version.id}-${idx}`}
          rawPrompt={rawPrompt}
          workspaceName={workspaceName}
          search={search}
          defaultOpen={idx < 2}
        />
      ))}
    </div>
  );
};

export default PromptsTab;
