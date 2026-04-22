import React, { useState } from "react";
import { Undo2 } from "lucide-react";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import { Button } from "@/ui/button";
import { useLayoutDialog } from "@/hooks/useLayoutDialog";
import { useActiveProjectId } from "@/store/AppStore";
import useProjectById from "@/api/projects/useProjectById";
import { INTEGRATIONS } from "@/constants/integrations";
import InstallWithAITab from "@/v2/pages-shared/onboarding/IntegrationsTabs/InstallWithAITab";
import ManualIntegrationList from "@/v2/pages-shared/onboarding/IntegrationsTabs/ManualIntegrationList";
import ManualIntegrationDetail from "@/v2/pages-shared/onboarding/IntegrationsTabs/ManualIntegrationDetail";

const AI_ASSISTED_TAB = "ai-assisted";
const MANUAL_TAB = "manual";

const useOpenQuickStartDialog = () => useLayoutDialog("quickstart");

const QuickstartContent: React.FC = () => {
  const projectId = useActiveProjectId();
  const { data: project } = useProjectById(
    { projectId: projectId ?? "" },
    { enabled: !!projectId },
  );
  const agentName = project?.name ?? "";

  const [activeTab, setActiveTab] = useState(AI_ASSISTED_TAB);
  const [manualCategory, setManualCategory] = useState<string | null>(null);
  const [selectedIntegrationId, setSelectedIntegrationId] = useState<
    string | null
  >(null);

  const selectedIntegration = selectedIntegrationId
    ? INTEGRATIONS.find((i) => i.id === selectedIntegrationId)
    : undefined;

  const handleTabChange = (value: string) => {
    setActiveTab(value);
    setSelectedIntegrationId(null);
  };

  if (selectedIntegration) {
    return (
      <div className="flex flex-col gap-5">
        <Button
          variant="outline"
          size="xs"
          onClick={() => setSelectedIntegrationId(null)}
          className="w-fit"
          id="quickstart-integration-back"
          data-fs-element="QuickstartIntegrationBack"
        >
          <Undo2 className="mr-1 size-3" />
          Back to integrations
        </Button>
        <ManualIntegrationDetail
          integration={selectedIntegration}
          agentName={agentName}
        />
      </div>
    );
  }

  return (
    <Tabs value={activeTab} onValueChange={handleTabChange}>
      <TabsList variant="underline">
        <TabsTrigger value={AI_ASSISTED_TAB} variant="underline">
          AI-assisted setup
        </TabsTrigger>
        <TabsTrigger value={MANUAL_TAB} variant="underline">
          Manual setup
        </TabsTrigger>
      </TabsList>
      <TabsContent value={AI_ASSISTED_TAB}>
        <InstallWithAITab agentName={agentName} showWaitingStep={false} />
      </TabsContent>
      <TabsContent value={MANUAL_TAB}>
        <ManualIntegrationList
          agentName={agentName}
          onSelectIntegration={setSelectedIntegrationId}
          activeCategory={manualCategory}
          onCategoryChange={setManualCategory}
        />
      </TabsContent>
    </Tabs>
  );
};

const QuickstartDialog: React.FC = () => {
  const { isOpen, setOpen } = useOpenQuickStartDialog();

  return (
    <Dialog open={isOpen} onOpenChange={setOpen}>
      <DialogContent className="max-w-[720px] gap-2">
        <DialogHeader>
          <DialogTitle>Quickstart guide</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <QuickstartContent />
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export { useOpenQuickStartDialog };
export default QuickstartDialog;
