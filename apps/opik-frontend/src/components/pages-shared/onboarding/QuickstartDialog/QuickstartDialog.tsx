import SideDialog from "@/components/shared/SideDialog/SideDialog";
import React from "react";
import { SheetTitle } from "@/components/ui/sheet";
import { IntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer";
import { useOpenQuickStartDialog } from "@/hooks/useOpenQuickStartDialog";

const QuickstartDialog: React.FC = () => {
  const { isOpen, setOpen } = useOpenQuickStartDialog();

  return (
    <SideDialog open={isOpen} setOpen={setOpen}>
      <div className="flex w-full min-w-fit flex-col px-20 pb-20">
        <SheetTitle className="comet-title-xl my-3 text-left">
          Quickstart guide
        </SheetTitle>
        <div className="comet-body-s mb-10 text-muted-slate">
          Opik helps you improve your LLM features by tracking what happens
          behind the scenes. Integrate Opik to unlock evaluations, experiments,
          and debugging.
        </div>

        <IntegrationExplorer source="quickstart-dialog">
          <div className="mb-8 flex items-center justify-between gap-6">
            <IntegrationExplorer.Search />

            <div className="flex items-center gap-3">
              <IntegrationExplorer.CopyApiKey />
              <IntegrationExplorer.GetHelp />
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
    </SideDialog>
  );
};

export { useOpenQuickStartDialog };
export default QuickstartDialog;
