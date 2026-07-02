import React, { useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { usePromptIdFromURL } from "@/v2/pages/PromptPage/usePromptIdFromURL";
import usePromptById from "@/api/prompts/usePromptById";
import PromptTab from "@/v2/pages/PromptPage/PromptTab/PromptTab";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import BackButton from "@/shared/BackButton/BackButton";
import DateTag from "@/shared/DateTag/DateTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import PromptTagsList from "@/v2/pages/PromptPage/PromptTagsList";
import { Separator } from "@/ui/separator";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import { usePermissions } from "@/contexts/PermissionsContext";
import ExperimentsTab from "./ExperimentsTab/ExperimentsTab";

const PromptPage: React.FunctionComponent = () => {
  const [tab, setTab] = useQueryParam("tab", StringParam);

  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  const promptId = usePromptIdFromURL();

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
          <BackButton
            to="/$workspaceName/projects/$projectId/prompts"
            tooltip="Back to prompts"
          />
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
            <DateTag date={prompt.created_at} resource={RESOURCE_TYPE.prompt} />
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
