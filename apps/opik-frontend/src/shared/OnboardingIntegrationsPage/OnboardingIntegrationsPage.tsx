import React from "react";

type IntegrationExplorerComponent = React.FC<{
  source?: string;
  children: React.ReactNode;
}> & {
  Search: React.ComponentType;
  QuickInstall: React.ComponentType;
  TypeScriptSDK: React.ComponentType;
  GetHelp: React.ComponentType;
  CopyApiKey: React.ComponentType;
  Skip: React.ComponentType;
  Tabs: React.FC<{ children: React.ReactNode }>;
  Grid: React.ComponentType;
};

type OnboardingIntegrationsPageProps = {
  IntegrationExplorer: IntegrationExplorerComponent;
  source: string;
};

const OnboardingIntegrationsPage: React.FC<OnboardingIntegrationsPageProps> = ({
  IntegrationExplorer,
  source,
}) => {
  return (
    <div className="mx-auto w-full max-w-[1040px] pb-10">
      <h1 className="md:comet-title-xl comet-title-l mb-3 mt-6 md:mt-10">
        Get started with Opik
      </h1>
      <p className="comet-body-s mb-10 text-muted-slate">
        Opik helps you improve your LLM features by tracking what happens behind
        the scenes. Integrate Opik to unlock evaluations, experiments, and
        debugging.
      </p>

      <IntegrationExplorer source={source}>
        <div className="mb-8 flex flex-col justify-between gap-6 md:flex-row md:items-center">
          <IntegrationExplorer.Search />
          <div className="flex flex-wrap items-center gap-6 md:gap-3">
            <IntegrationExplorer.CopyApiKey />
            <IntegrationExplorer.GetHelp />
            <IntegrationExplorer.Skip />
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
  );
};

export default OnboardingIntegrationsPage;
