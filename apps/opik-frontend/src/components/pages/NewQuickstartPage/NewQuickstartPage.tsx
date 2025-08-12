import React, { useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { ChevronsRight, HelpCircle, Plus } from "lucide-react";
import IntegrationCard from "./IntegrationCard";
import RequestIntegrationDialog from "./RequestIntegrationDialog";
import HelpGuideDialog from "./HelpGuideDialog";
import IntegrationDetailsDialog from "./IntegrationDetailsDialog";
import QuickInstallDialog from "./QuickInstallDialog";
import cursorLogo from "/images/integrations/cursor.png";
import copilotLogo from "/images/integrations/copilot.png";
import windsurfLogo from "/images/integrations/windsurf.png";
import {
  INTEGRATION_CATEGORIES,
  getIntegrationsByCategory,
  INTEGRATIONS,
} from "@/constants/integrations";
import ApiKeyCopyButton from "@/components/shared/ApiKeyCopyButton/ApiKeyCopyButton";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

const NewQuickstartPage: React.FunctionComponent = () => {
  const [searchText, setSearchText] = useState("");
  const [activeTab, setActiveTab] = useState<string>(
    INTEGRATION_CATEGORIES.ALL,
  );
  const [requestIntegrationDialogOpen, setRequestIntegrationDialogOpen] =
    useState(false);
  const [helpGuideDialogOpen, setHelpGuideDialogOpen] = useState(false);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [selectedIntegrationId, setSelectedIntegrationId] = useQueryParam(
    "integration",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const handleGetHelp = () => {
    setHelpGuideDialogOpen(true);
  };

  const handleSearchChange = (value: string) => {
    setSearchText(value);
  };

  const handleRequestIntegration = () => {
    setRequestIntegrationDialogOpen(true);
  };

  const handleIntegrationClick = (integrationId: string) => {
    setSelectedIntegrationId(integrationId);
  };

  const selectedIntegration = INTEGRATIONS.find(
    (integration) => integration.id === selectedIntegrationId,
  );

  const handleCloseIntegrationDialog = () => {
    setSelectedIntegrationId(undefined);
  };

  const integrations = getIntegrationsByCategory(activeTab);
  const filteredIntegrations = !searchText
    ? integrations
    : integrations.filter((integration) => {
        const title = integration.title.toLowerCase();
        const description = (integration.description || "").toLowerCase();
        const query = searchText.toLowerCase();
        return title.includes(query) || description.includes(query);
      });

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

        <div className="mb-8 flex items-center justify-between gap-6">
          <SearchInput
            searchText={searchText}
            setSearchText={handleSearchChange}
            placeholder="Search integration"
            className="max-w-[240px]"
            size="sm"
          />

          <div className="flex items-center gap-3">
            <ApiKeyCopyButton />
            <Button
              className="hidden"
              variant="outline"
              size="sm"
              onClick={handleGetHelp}
            >
              <HelpCircle className="mr-1.5 size-3.5" />
              Get help
            </Button>
            <Button variant="ghost" size="sm" asChild>
              <Link to="/$workspaceName/home" params={{ workspaceName }}>
                Skip & explore platform
                <ChevronsRight className="ml-1.5 size-3.5" />
              </Link>
            </Button>
          </div>
        </div>

        <IntegrationCard
          title="Quick install with AI assistants"
          description="Set up Opik fast with Cursor, Copilot, Windsurf, or your favorite AI assistant."
          icon={
            <div className="flex gap-1 pr-2">
              <img
                alt="Cursor"
                src={cursorLogo}
                className="size-[32px] shrink-0"
              />
              <img
                alt="Copilot"
                src={copilotLogo}
                className="size-[32px] shrink-0"
              />
              <img
                alt="Windsurf"
                src={windsurfLogo}
                className="size-[32px] shrink-0"
              />
            </div>
          }
          tag="New"
          className="mb-4"
          onClick={() => handleIntegrationClick("quick-opik-install")}
        />

        <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
          <TabsList variant="underline" className="w-full justify-start">
            <TabsTrigger value={INTEGRATION_CATEGORIES.ALL} variant="underline">
              {INTEGRATION_CATEGORIES.ALL}
            </TabsTrigger>
            <TabsTrigger
              value={INTEGRATION_CATEGORIES.LLM_PROVIDERS}
              variant="underline"
            >
              {INTEGRATION_CATEGORIES.LLM_PROVIDERS}
            </TabsTrigger>
            <TabsTrigger
              value={INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS}
              variant="underline"
            >
              {INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS}
            </TabsTrigger>
            {/* <TabsTrigger
              value={INTEGRATION_CATEGORIES.AGENTS_OPTIMIZATION}
              variant="underline"
            >
              {INTEGRATION_CATEGORIES.AGENTS_OPTIMIZATION}
            </TabsTrigger> */}
          </TabsList>

          <TabsContent value={activeTab} className="mt-6">
            {filteredIntegrations.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-center">
                <h3 className="comet-body-s mb-2 text-muted-slate">
                  No search results
                </h3>
                <Button
                  variant="link"
                  size="sm"
                  onClick={handleRequestIntegration}
                >
                  Request integration
                </Button>
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
                {filteredIntegrations.map((integration) => {
                  return (
                    <IntegrationCard
                      key={integration.id}
                      title={integration.title}
                      description={integration.description}
                      icon={
                        <img
                          alt={integration.title}
                          src={integration.icon}
                          className="size-[40px] shrink-0"
                        />
                      }
                      tag={integration.isHidden ? "new" : integration.tag}
                      onClick={() => handleIntegrationClick(integration.id)}
                    />
                  );
                })}

                <IntegrationCard
                  title="Request integration"
                  iconClassName="min-w-0"
                  className="justify-center"
                  icon={<Plus className="size-4" />}
                  onClick={handleRequestIntegration}
                />
              </div>
            )}
          </TabsContent>
        </Tabs>
      </div>

      <RequestIntegrationDialog
        open={requestIntegrationDialogOpen}
        setOpen={setRequestIntegrationDialogOpen}
      />
      <HelpGuideDialog
        open={helpGuideDialogOpen}
        setOpen={setHelpGuideDialogOpen}
      />
      <QuickInstallDialog
        open={selectedIntegrationId === "quick-opik-install"}
        onClose={handleCloseIntegrationDialog}
      />
      <IntegrationDetailsDialog
        selectedIntegration={selectedIntegration || null}
        onClose={handleCloseIntegrationDialog}
      />
    </div>
  );
};

export default NewQuickstartPage;
