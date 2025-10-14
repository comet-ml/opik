import React from "react";
import IntegrationCard from "@/components/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import { BookOpen } from "lucide-react";
import { buildDocsUrl } from "@/lib/utils";

const IntegrationDocumentation: React.FC = () => {
  return (
    <a
      href={buildDocsUrl(
        "/integrations/overview",
        "&utm_source=opik_frontend&utm_medium=integration_explorer&utm_campaign=integrations_docs&utm_content=integration_card",
      )}
      target="_blank"
      rel="noopener noreferrer"
      data-fs-element="IntegrationsOverviewDocsLink"
      id="integrations-overview-docs-link"
    >
      <IntegrationCard
        title="View all integrations"
        description="Discover 60+ ways to connect"
        size="lg"
        icon={
          <div className="flex size-[32px] items-center justify-center rounded-lg bg-primary/10">
            <BookOpen className="size-5 text-primary" />
          </div>
        }
      />
    </a>
  );
};

export default IntegrationDocumentation;
