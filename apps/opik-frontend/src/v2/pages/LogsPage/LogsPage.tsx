import { useActiveProjectId } from "@/store/AppStore";
import useProjectById from "@/api/projects/useProjectById";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/v2/pages/LogsPage/LogsTab";
import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
import { Construction } from "lucide-react";
import { useState } from "react";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import SetGuardrailDialog from "@/v2/pages-shared/traces/GuardrailConfig/SetGuardrailDialog";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import useLogsType from "@/v2/pages/LogsPage/useLogsType";
import { resolveProjectDateRangeDefault } from "@/v2/pages-shared/traces/MetricDateRangeSelect";

const LogsPage = () => {
  const projectId = useActiveProjectId()!;
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );
  const { data: project, isPending: isProjectPending } = useProjectById(
    {
      projectId,
    },
    {
      refetchOnMount: false,
    },
  );

  const projectName = project?.name || projectId;

  // Resolved here, from the query this page already owns, and passed to every consumer below. They
  // share one date-range key, so they have to agree; deriving it once removes the possibility of
  // disagreeing. Note project?.name rather than projectName — the latter falls back to the raw id
  // while loading, which would read as "not the demo project".
  const dateRangeDefault = resolveProjectDateRangeDefault(
    project?.name,
    !isProjectPending,
  );

  const { logsType, needsDefaultResolution, setLogsType } = useLogsType({
    projectId,
    dateRangeDefault,
  });

  const openGuardrailsDialog = () => setIsGuardrailsDialogOpened(true);

  return (
    <>
      <PageBodyScrollContainer>
        <PageBodyStickyContainer
          className="mb-3 mt-6 flex items-center justify-between"
          direction="horizontal"
        >
          <h1 className="comet-body-accented truncate break-words">Logs</h1>
          {isGuardrailsEnabled && (
            <div className="flex shrink-0 items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={openGuardrailsDialog}
              >
                <Construction className="mr-1.5 size-3.5" />
                Set a guardrail
              </Button>
            </div>
          )}
        </PageBodyStickyContainer>
        {/* Also waits on the project, not just the logs-type default: use-local-storage-state
            captures its `defaultValue` once, with useState, so a tab that mounts before the project
            name is known would freeze the placeholder 30-day default and keep it after the real one
            arrives. Mounting the tabs only once it is settled makes the captured value the right one
            by construction. A failed lookup reports not-pending, so this cannot hang. */}
        {needsDefaultResolution || isProjectPending ? (
          <Loader />
        ) : (
          <LogsTab
            projectId={projectId}
            projectName={projectName}
            dateRangeDefault={dateRangeDefault}
            logsType={logsType}
            onLogsTypeChange={setLogsType}
          />
        )}
      </PageBodyScrollContainer>
      {isGuardrailsEnabled && (
        <SetGuardrailDialog
          open={isGuardrailsDialogOpened}
          setOpen={setIsGuardrailsDialogOpened}
          projectName={projectName}
        />
      )}
    </>
  );
};

export default LogsPage;
