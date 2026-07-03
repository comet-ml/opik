import React, { useMemo } from "react";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import TrialKPICards from "./TrialKPICards";
import TrialItemsTab from "./TrialsItemsTab/TrialItemsTab";
import TrialConfigurationSection from "@/v2/pages-shared/experiments/TrialConfigurationSection";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import type { TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";
import TrialStatusCard from "./TrialStatusCard";
import { resolveParentExperiment } from "./resolveTrialLineage";
import type { TrialSidebarTab } from "./useTrialSidebarState";

type TrialSidebarContentProps = {
  optimization?: Optimization;
  /** Experiment ids from the URL — passed through to the items table. */
  experimentIds: string[];
  /** The open trial's experiments (resolved from the run's loaded list). */
  trialExperiments: Experiment[];
  /** Every experiment of the run — baseline metrics + parent resolution. */
  allExperiments: Experiment[];
  baselineExperiment?: Experiment;
  isTestSuite?: boolean;
  status?: TrialStatus;
  isBest?: boolean;
  stepIndex?: number;
  tab: TrialSidebarTab;
  /** "diff" opens the Prompt section straight in diff-vs-baseline view. */
  promptView?: "config" | "diff";
  onTabChange: (tab: string) => void;
};

/**
 * Body of the trial sidebar: segmented Results | Prompt tabs over the same
 * content the trial page used to render. Wrapped in PageBodyScrollContainer so
 * the sticky headers and the virtualized items table keep working inside the
 * panel exactly as they do on a page.
 */
const TrialSidebarContent: React.FC<TrialSidebarContentProps> = ({
  optimization,
  experimentIds,
  trialExperiments,
  allExperiments,
  baselineExperiment,
  isTestSuite,
  status,
  isBest,
  stepIndex,
  tab,
  promptView = "config",
  onTabChange,
}) => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const trialExperiment = trialExperiments[0];

  const parentExperiment = useMemo(
    () => resolveParentExperiment(trialExperiment, allExperiments),
    [trialExperiment, allExperiments],
  );

  return (
    <PageBodyScrollContainer className="mx-0 h-full bg-soft-background">
      <Tabs value={tab} onValueChange={onTabChange} className="min-w-min">
        <PageBodyStickyContainer
          direction="horizontal"
          limitWidth
          className="mb-6 pt-4"
        >
          <TabsList variant="segmented-primary">
            <TabsTrigger variant="segmented-primary" size="sm" value="results">
              Results
            </TabsTrigger>
            <TabsTrigger variant="segmented-primary" size="sm" value="prompt">
              Prompt
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>

        <TabsContent value="results" className="mt-0">
          <PageBodyStickyContainer
            direction="horizontal"
            limitWidth
            className="mb-6"
          >
            <TrialKPICards
              className="grid-cols-4"
              experiments={trialExperiments}
              allOptimizationExperiments={allExperiments}
              objectiveName={optimization?.objective_name}
              isTestSuite={isTestSuite}
            >
              <TrialStatusCard
                status={status}
                isBest={isBest}
                isTestSuite={isTestSuite}
                stepIndex={stepIndex}
                createdAt={trialExperiment?.created_at}
              />
            </TrialKPICards>
          </PageBodyStickyContainer>

          {canViewDatasets && (
            <>
              <PageBodyStickyContainer direction="horizontal" limitWidth>
                <h2 className="comet-title-s mb-4">
                  {isTestSuite ? "Test items" : "Evaluation results"}
                </h2>
              </PageBodyStickyContainer>
              <TrialItemsTab
                objectiveName={optimization?.objective_name}
                datasetId={optimization?.dataset_id ?? ""}
                experimentsIds={experimentIds}
                experiments={trialExperiments}
                isTestSuite={isTestSuite}
              />
            </>
          )}
        </TabsContent>

        <TabsContent value="prompt" className="mt-0">
          <PageBodyStickyContainer
            direction="horizontal"
            limitWidth
            className="mb-6"
          >
            <TrialConfigurationSection
              // Remount on trial/view change so the diff button's "open in
              // diff view" intent applies even while the sidebar stays open.
              key={`${trialExperiment?.id}-${promptView}`}
              title="Prompt"
              experiments={trialExperiments}
              referenceExperiment={baselineExperiment}
              parentExperiment={parentExperiment}
              studioConfig={optimization?.studio_config}
              defaultViewMode={
                promptView === "diff" && baselineExperiment
                  ? "diff-baseline"
                  : "config"
              }
            />
          </PageBodyStickyContainer>
        </TabsContent>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default TrialSidebarContent;
