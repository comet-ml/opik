import React, { useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { usePromptIdFromURL } from "@/hooks/usePromptIdFromURL";
import usePromptById from "@/api/prompts/usePromptById";
import DateTag from "@/components/shared/DateTag/DateTag";
import PromptTab from "@/components/pages/PromptPage/PromptTab/PromptTab";
import CommitsTab from "@/components/pages/PromptPage/CommitsTab/CommitsTab";
import ExperimentsTab from "@/components/pages/PromptPage/ExperimentsTab/ExperimentsTab";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import PromptTagsList from "@/components/pages/PromptPage/PromptTagsList";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";

const PromptPage: React.FunctionComponent = () => {
  const [tab, setTab] = useQueryParam("tab", StringParam);

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
        className="mb-4 mt-6 flex min-h-8 items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words">{promptName}</h1>
      </PageBodyStickyContainer>
      {prompt?.description && (
        <PageBodyStickyContainer
          className="-mt-3 mb-4 flex min-h-8 items-center justify-between"
          direction="horizontal"
        >
          <div className="text-muted-slate">{prompt.description}</div>
        </PageBodyStickyContainer>
      )}
      <PageBodyStickyContainer
        className="pb-4"
        direction="horizontal"
        limitWidth
      >
        {prompt?.created_at && (
          <div className="mb-2 flex gap-4 overflow-x-auto">
            <DateTag date={prompt?.created_at} />
          </div>
        )}
        <PromptTagsList
          tags={prompt?.tags ?? []}
          promptId={promptId}
          prompt={prompt}
        />
      </PageBodyStickyContainer>
      <Tabs
        defaultValue="prompt"
        value={tab as string}
        onValueChange={setTab}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="prompt">
              Prompt
            </TabsTrigger>
            <TabsTrigger variant="underline" value="experiments">
              Experiments
              <ExplainerIcon
                className="ml-1"
                {...EXPLAINERS_MAP[
                  EXPLAINER_ID.why_do_i_have_experiments_in_the_prompt_library
                ]}
              />
            </TabsTrigger>
            <TabsTrigger variant="underline" value="commits">
              Commits
              <ExplainerIcon
                className="ml-1"
                {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_commits]}
              />
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value="prompt">
          <PromptTab prompt={prompt} />
        </TabsContent>
        <TabsContent value="experiments">
          <ExperimentsTab promptId={promptId} />
        </TabsContent>
        <TabsContent value="commits">
          <CommitsTab prompt={prompt} />
        </TabsContent>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default PromptPage;
