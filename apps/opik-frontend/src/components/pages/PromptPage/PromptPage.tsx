import React, { useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { usePromptIdFromURL } from "@/hooks/usePromptIdFromURL";
import usePromptById from "@/api/prompts/usePromptById";
import DateTag from "@/components/shared/DateTag/DateTag";
import PromptTab from "@/components/pages/PromptPage/PromptTab/PromptTab";

const PromptPage: React.FunctionComponent = () => {
  const [tab, setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const promptId = usePromptIdFromURL();

  const { data: prompt } = usePromptById({ promptId }, { enabled: !!promptId });
  const promptName = prompt?.name || "";
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  useEffect(() => {
    if (promptId && promptName) {
      setBreadcrumbParam("promptId", promptId, promptName);
    }
  }, [promptId, promptName]);

  useEffect(() => {
    if (!tab) {
      setTab("prompt");
    }
  }, [tab]);

  return (
    <div className="pt-6">
      <div className="pb-4">
        <div className="mb-4 flex min-h-8 items-center justify-between">
          <h1 className="comet-title-l truncate break-words">{promptName}</h1>
        </div>

        {prompt?.created_at && (
          <div className="mb-1 flex gap-4 overflow-x-auto">
            <DateTag date={prompt?.created_at} />
          </div>
        )}
      </div>

      <Tabs defaultValue="prompt" value={tab as string} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="prompt">
            Prompt
          </TabsTrigger>
          <TabsTrigger variant="underline" value="experiments">
            Experiments
          </TabsTrigger>
          <TabsTrigger variant="underline" value="commits">
            Commits
          </TabsTrigger>
        </TabsList>
        <TabsContent value="prompt">
          <PromptTab prompt={prompt} />
        </TabsContent>
        <TabsContent value="experiments">Experiments</TabsContent>
        <TabsContent value="commits">Commits</TabsContent>
      </Tabs>
    </div>
  );
};

export default PromptPage;
