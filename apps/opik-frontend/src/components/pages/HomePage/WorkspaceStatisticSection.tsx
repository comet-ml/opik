import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";

import useProjectsList from "@/api/projects/useProjectsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import usePromptsList from "@/api/prompts/usePromptsList";
import useAppStore from "@/store/AppStore";
import { formatNumberInK } from "@/lib/utils";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { FileTerminal, FlaskConical, LayoutGrid } from "lucide-react";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const WorkspaceStatisticSection = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const { data: projectData } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const { data: experimentsData } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const { data: promptsData } = usePromptsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  return (
    <div className="flex flex-wrap gap-4">
      <Card
        className="min-w-52 flex-1 cursor-pointer hover:shadow-md"
        onClick={() =>
          navigate({
            to: "/$workspaceName/projects",
            params: {
              workspaceName,
            },
          })
        }
      >
        <CardHeader className="flex flex-row items-center gap-3">
          <div className="flex size-6 items-center justify-center rounded-sm bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]">
            <LayoutGrid className="size-3.5" />
          </div>
          <div className="comet-body-s !m-0 flex items-center gap-1.5">
            Projects
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.what_do_you_use_projects_for]}
            />
          </div>
        </CardHeader>
        <CardContent>
          <div className="comet-title-l truncate">
            {formatNumberInK(projectData?.total ?? 0)}
          </div>
        </CardContent>
      </Card>
      <Card
        className="min-w-52 flex-1 cursor-pointer hover:shadow-md"
        onClick={() =>
          navigate({
            to: "/$workspaceName/experiments",
            params: {
              workspaceName,
            },
          })
        }
      >
        <CardHeader className="flex flex-row items-center gap-3">
          <div className="flex size-6 items-center justify-center rounded-sm bg-[var(--tag-burgundy-bg)] text-[var(--tag-burgundy-text)]">
            <FlaskConical className="size-3.5" />
          </div>
          <div className="comet-body-s !m-0 flex items-center gap-1.5">
            Experiments
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_experiment]}
            />
          </div>
        </CardHeader>
        <CardContent>
          <div className="comet-title-l truncate">
            {formatNumberInK(experimentsData?.total ?? 0)}
          </div>
        </CardContent>
      </Card>
      <Card
        className="min-w-52 flex-1 cursor-pointer hover:shadow-md"
        onClick={() =>
          navigate({
            to: "/$workspaceName/prompts",
            params: {
              workspaceName,
            },
          })
        }
      >
        <CardHeader className="flex flex-row items-center gap-3">
          <div className="flex size-6 items-center justify-center rounded-sm bg-[var(--tag-purple-bg)] text-[var(--tag-purple-text)]">
            <FileTerminal className="size-3.5" />
          </div>
          <div className="comet-body-s !m-0 flex items-center gap-1.5">
            Prompts
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_prompt_library]}
            />
          </div>
        </CardHeader>
        <CardContent>
          <div className="comet-title-l truncate">
            {formatNumberInK(promptsData?.total ?? 0)}
          </div>
        </CardContent>
      </Card>
    </div>
  );
};

export default WorkspaceStatisticSection;
