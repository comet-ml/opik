import React, { useEffect } from "react";
import { ArrowLeft, History } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { usePromptIdFromURL } from "@/v2/pages/PromptPage/usePromptIdFromURL";
import usePromptById from "@/api/prompts/usePromptById";
import PromptTab from "@/v2/pages/PromptPage/PromptTab/PromptTab";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import PromptTagsList from "@/v2/pages/PromptPage/PromptTagsList";
import { Separator } from "@/ui/separator";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import { usePermissions } from "@/contexts/PermissionsContext";
import ExperimentsTab from "./ExperimentsTab/ExperimentsTab";

const PromptPage: React.FunctionComponent = () => {
  const [tab, setTab] = useQueryParam("tab", StringParam);

  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  const promptId = usePromptIdFromURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const { data: prompt } = usePromptById({ promptId }, { enabled: !!promptId });
  const promptName = prompt?.name || "";
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  useEffect(() => {
    if (promptId && promptName) {
      setBreadcrumbParam("promptId", promptId, promptName);
    }
  }, [promptId, promptName, setBreadcrumbParam]);

  useEffect(() => {
    if (!tab) {
      setTab("prompt", "replaceIn");
    }
  }, [tab, setTab]);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-2 mt-6 flex min-h-8 items-center justify-between"
        direction="horizontal"
      >
        <div className="flex min-w-0 items-center gap-2">
          <TooltipWrapper content="Back to prompts">
            <Link
              to="/$workspaceName/projects/$projectId/prompts"
              params={{ workspaceName, projectId: activeProjectId! }}
              className="flex size-6 shrink-0 items-center justify-center rounded-md text-foreground hover:bg-primary-foreground"
            >
              <ArrowLeft className="size-4" />
            </Link>
          </TooltipWrapper>
          <h1 className="comet-title-xs truncate break-words">{promptName}</h1>
        </div>
      </PageBodyStickyContainer>
      {prompt?.description && (
        <PageBodyStickyContainer
          className="-mt-3 flex min-h-8 items-center justify-between"
          direction="horizontal"
        >
          <div className="comet-body-s text-muted-slate">
            {prompt.description}
          </div>
        </PageBodyStickyContainer>
      )}
      <PageBodyStickyContainer
        className="pb-4"
        direction="horizontal"
        limitWidth
      >
        <div className="flex items-center overflow-x-auto">
          {prompt?.created_at && (
            <TooltipWrapper content="Prompt creation time">
              <div className="comet-body-s flex shrink-0 items-center gap-1.5 text-muted-slate">
                <History className="size-3.5 shrink-0" />
                <span>{formatDate(prompt.created_at)}</span>
              </div>
            </TooltipWrapper>
          )}
          <Separator orientation="vertical" className="mx-2 h-4" />
          <PromptTagsList
            tags={prompt?.tags ?? []}
            promptId={promptId}
            prompt={prompt}
          />
        </div>
      </PageBodyStickyContainer>
      <Tabs
        defaultValue="prompt"
        value={tab as string}
        onValueChange={setTab}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="segmented-primary">
            <TabsTrigger variant="segmented-primary" value="prompt">
              Prompt
            </TabsTrigger>
            {canViewExperiments && (
              <TabsTrigger variant="segmented-primary" value="experiments">
                Experiments
                <ExplainerIcon
                  className="ml-1"
                  {...EXPLAINERS_MAP[
                    EXPLAINER_ID.why_do_i_have_experiments_in_the_prompt_library
                  ]}
                />
              </TabsTrigger>
            )}
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value="prompt">
          <PromptTab prompt={prompt} />
        </TabsContent>
        {canViewExperiments && (
          <TabsContent value="experiments">
            <ExperimentsTab promptId={promptId} />
          </TabsContent>
        )}
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default PromptPage;
