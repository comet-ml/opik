import React from "react";
import { Database } from "lucide-react";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import NavigationTag from "@/shared/NavigationTag";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import TraceLogsSidebarButton from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton";
import { usePermissions } from "@/contexts/PermissionsContext";
import { LOGS_SOURCE } from "@/types/traces";
import { generateExperimentIdsFilter } from "@/lib/filters";
import { Experiment } from "@/types/datasets";

type TrialSidebarProps = {
  open: boolean;
  onClose: () => void;
  trialNumber?: number;
  /** The open candidate is the run's baseline, which is not a trial and has
   *  no number — the header says "Baseline" instead of "Trial #N". */
  isBaseline?: boolean;
  /** The trial's experiments, resolved from the run's already-loaded list. */
  trialExperiments: Experiment[];
  children?: React.ReactNode;
};

/**
 * Right side panel hosting the single-trial view over the run overview —
 * same shell + slide animation as the new-run form sidebar. The panel is
 * always mounted (the slide is driven by `open`); content mounts only while
 * open, so a closed sidebar runs no queries.
 */
const TrialSidebar: React.FC<TrialSidebarProps> = ({
  open,
  onClose,
  trialNumber,
  isBaseline,
  trialExperiments,
  children,
}) => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const experiment = trialExperiments[0];
  const title = isBaseline
    ? "Baseline"
    : trialNumber
      ? `Trial #${trialNumber}`
      : "Trial";

  return (
    <ResizableSidePanel
      panelId="optimization-trial-sidebar"
      entity="trial"
      open={open}
      onClose={onClose}
      initialWidth={0.65}
      minWidth={640}
      // Align the header's chevron icon (6px inset in its 24px button) with the
      // body content's 24px (px-6) left edge: 24 − 6 = 18px.
      headerClassName="pl-[18px]"
      header={
        <ResizableSidePanelTopBar title={title} onClose={onClose}>
          {canViewDatasets &&
            experiment?.dataset_id &&
            experiment?.dataset_name && (
              <NavigationTag
                id={experiment.dataset_id}
                name={experiment.dataset_name}
                resource={RESOURCE_TYPE.dataset}
                icon={Database}
                textSize="xs"
                className="rounded-md"
              />
            )}
          {experiment?.project_id && (
            <TraceLogsSidebarButton
              projectId={experiment.project_id}
              logsSource={LOGS_SOURCE.optimization}
              sourceFilters={generateExperimentIdsFilter(
                trialExperiments.map((e) => e.id),
              )}
              // Locked, not seeded: these are this optimization's trial logs, so the scope has to
              // hold. Seeding it into the editable filter bar no longer works — the chip bar owns
              // that key and drops any filter without a matching chip, which there is none for
              // experiment ids, so the overlay would fall back to the whole project. The label is
              // what keeps the lock visible: without it the narrowing is silent.
              lockScope
              scopeLabel={title}
              variant="nav"
              label="Logs"
              textSize="xs"
              title="Optimization logs"
            />
          )}
        </ResizableSidePanelTopBar>
      }
    >
      {open && children}
    </ResizableSidePanel>
  );
};

export default TrialSidebar;
