import React, { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { BookOpen, Plus } from "lucide-react";
import { useTheme } from "@/components/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import IntegrationCard from "@/components/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import RequestIntegrationDialog from "@/components/pages-shared/onboarding/IntegrationExplorer/components/RequestIntegrationDialog";
import IntegrationDetailsDialog from "@/components/pages-shared/onboarding/IntegrationExplorer/components/IntegrationDetailsDialog";
import { useIntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";
import { getIntegrationsByCategory } from "@/constants/integrations";
import { buildDocsUrl, cn } from "@/lib/utils";

type IntegrationGridProps = {
  className?: string;
};

const IntegrationGrid: React.FunctionComponent<IntegrationGridProps> = ({
  className,
}) => {
  const {
    activeTab,
    searchText,
    selectedIntegrationId,
    setSelectedIntegrationId,
    source,
  } = useIntegrationExplorer();
  const [requestIntegrationDialogOpen, setRequestIntegrationDialogOpen] =
    useState(false);
  const { themeMode } = useTheme();

  const handleRequestIntegration = () => {
    setRequestIntegrationDialogOpen(true);
  };

  const integrations = getIntegrationsByCategory(activeTab);

  const filteredIntegrations = useMemo(() => {
    if (!searchText) return integrations;

    return integrations.filter((integration) => {
      const title = integration.title.toLowerCase();
      const description = (integration.description || "").toLowerCase();
      const query = searchText.toLowerCase();
      return title.includes(query) || description.includes(query);
    });
  }, [integrations, searchText]);

  const selectedIntegration = useMemo(
    () =>
      integrations.find(
        (integration) => integration.id === selectedIntegrationId,
      ),
    [integrations, selectedIntegrationId],
  );

  if (filteredIntegrations.length === 0) {
    return (
      <>
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <h3 className="comet-body-s mb-2 text-muted-slate">
            No search results
          </h3>

          <Button
            variant="link"
            size="sm"
            onClick={handleRequestIntegration}
            id={`integration-grid-no-results-request${
              source ? `-${source}` : ""
            }`}
            data-fs-element={`IntegrationGridNoResultsRequest${
              source ? `-${source}` : ""
            }`}
          >
            Request integration
          </Button>
        </div>

        <RequestIntegrationDialog
          open={requestIntegrationDialogOpen}
          setOpen={setRequestIntegrationDialogOpen}
        />
      </>
    );
  }

  return (
    <>
      <div
        className={cn(
          "grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4",
          className,
        )}
      >
        {filteredIntegrations.map((integration) => {
          const integrationIdPascalCase = integration.id
            .split("-")
            .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
            .join("");

          return (
            <IntegrationCard
              key={integration.id}
              title={integration.title}
              description={integration.description}
              icon={
                <img
                  alt={integration.title}
                  src={
                    themeMode === THEME_MODE.DARK && integration.whiteIcon
                      ? integration.whiteIcon
                      : integration.icon
                  }
                  className="size-[40px] shrink-0"
                />
              }
              tag={integration.tag}
              onClick={() => setSelectedIntegrationId(integration.id)}
              id={`integration-card-${integration.id}${
                source ? `-${source}` : ""
              }`}
              data-fs-element={`IntegrationCard${integrationIdPascalCase}${
                source ? `-${source}` : ""
              }`}
            />
          );
        })}

        <a
          href={buildDocsUrl(
            "/integrations/overview",
            "&utm_source=opik_frontend&utm_medium=integration_grid&utm_campaign=integrations_docs&utm_content=integration_card",
          )}
          target="_blank"
          rel="noopener noreferrer"
        >
          <IntegrationCard
            title="View all integrations"
            description="Discover 60+ ways to integrate"
            icon={
              <div className="flex size-[40px] items-center justify-center rounded-lg bg-primary/10">
                <BookOpen className="size-5 text-primary" />
              </div>
            }
            id={`integrations-overview-docs-link${source ? `-${source}` : ""}`}
            data-fs-element={`IntegrationsOverviewDocsLink${
              source ? `-${source}` : ""
            }`}
          />
        </a>

        <IntegrationCard
          title="Request integration"
          iconClassName="min-w-0 min-h-10"
          className="justify-center"
          icon={<Plus className="size-4" />}
          onClick={handleRequestIntegration}
          id={`integration-grid-request-integration${
            source ? `-${source}` : ""
          }`}
          data-fs-element={`IntegrationGridRequestIntegration${
            source ? `-${source}` : ""
          }`}
        />
      </div>

      <RequestIntegrationDialog
        open={requestIntegrationDialogOpen}
        setOpen={setRequestIntegrationDialogOpen}
      />

      <IntegrationDetailsDialog
        selectedIntegration={selectedIntegration}
        onClose={() => setSelectedIntegrationId(undefined)}
      />
    </>
  );
};

export default IntegrationGrid;
