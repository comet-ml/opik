import React from "react";

import { Skeleton } from "@/ui/skeleton";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import AgentRunnerPromptCard from "./AgentRunnerPromptCard";

type AgentRunnerPromptsListProps = {
  projectId: string;
};

const SkeletonCard: React.FC = () => (
  <div className="overflow-hidden rounded-md border border-border bg-soft-background">
    <div className="flex items-center gap-2 px-3 py-2">
      <Skeleton className="size-4 shrink-0" />
      <Skeleton className="h-4 w-40" />
      <Skeleton className="h-4 w-12" />
    </div>
    <div className="px-3 pb-3">
      <Skeleton className="h-16 w-full" />
    </div>
  </div>
);

const AgentRunnerPromptsList: React.FC<AgentRunnerPromptsListProps> = ({
  projectId,
}) => {
  const { data, isLoading } = useProjectPromptsList(
    {
      projectId,
      page: 1,
      size: 500,
    },
    { enabled: Boolean(projectId) },
  );

  if (isLoading) {
    return (
      <div className="flex flex-col gap-3">
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
      </div>
    );
  }

  const prompts = data?.content ?? [];

  if (prompts.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-border bg-soft-background px-4 py-6 text-center">
        <p className="comet-body-s text-muted-slate">
          No prompts in this project yet.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {prompts.map((prompt) => (
        <AgentRunnerPromptCard key={prompt.id} prompt={prompt} />
      ))}
    </div>
  );
};

export default AgentRunnerPromptsList;
