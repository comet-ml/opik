import React, { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Plus } from "lucide-react";
import IntegrationCard from "@/components/shared/IntegrationExplorer/components/IntegrationCard";
import RequestIntegrationDialog from "@/components/shared/IntegrationExplorer/components/RequestIntegrationDialog";
import IntegrationDetailsDialog from "@/components/shared/IntegrationExplorer/components/IntegrationDetailsDialog";
import { useIntegrationExplorer } from "@/components/shared/IntegrationExplorer/IntegrationExplorerContext";
import { getIntegrationsByCategory } from "@/constants/integrations";

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
  } = useIntegrationExplorer();
  const [requestIntegrationDialogOpen, setRequestIntegrationDialogOpen] =
    useState(false);

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

          <Button variant="link" size="sm" onClick={handleRequestIntegration}>
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
        className={`grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4 ${
          className || ""
        }`}
      >
        {filteredIntegrations.map((integration) => (
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
            tag={integration.tag}
            onClick={() => setSelectedIntegrationId(integration.id)}
          />
        ))}

        <IntegrationCard
          title="Request integration"
          iconClassName="min-w-0 min-h-10"
          className="justify-center"
          icon={<Plus className="size-4" />}
          onClick={handleRequestIntegration}
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
