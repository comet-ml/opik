import React from "react";
import { Button } from "@/components/ui/button";
import { ChevronsRight } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { IntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer";

const NewQuickstartPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="w-full pb-10">
      <div className="mx-auto max-w-[1040px]">
        <div className="mb-3 mt-10 flex items-center justify-between">
          <h1 className="comet-title-xl">Get started with Opik</h1>
          {/* <LoggedDataStatus status="waiting" /> */}
        </div>
        <div className="comet-body-s mb-10 text-muted-slate">
          Opik helps you improve your LLM features by tracking what happens
          behind the scenes. Integrate Opik to unlock evaluations, experiments,
          and debugging.
        </div>

        <IntegrationExplorer>
          <div className="mb-8 flex items-center justify-between gap-6">
            <IntegrationExplorer.Search />

            <div className="flex items-center gap-3">
              <IntegrationExplorer.CopyApiKey />
              <IntegrationExplorer.GetHelp />
              <Button variant="ghost" size="sm" asChild>
                <Link to="/$workspaceName/home" params={{ workspaceName }}>
                  Skip & explore platform
                  <ChevronsRight className="ml-1.5 size-3.5" />
                </Link>
              </Button>
            </div>
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <IntegrationExplorer.QuickInstall />
            <IntegrationExplorer.TypeScriptSDK />
          </div>

          <IntegrationExplorer.Tabs>
            <IntegrationExplorer.Grid />
          </IntegrationExplorer.Tabs>
        </IntegrationExplorer>
      </div>
    </div>
  );
};

export default NewQuickstartPage;
