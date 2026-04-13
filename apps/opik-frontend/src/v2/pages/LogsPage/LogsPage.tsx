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

const LogsPage = () => {
  const projectId = useActiveProjectId()!;
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );
  const { data: project } = useProjectById(
    {
      projectId,
    },
    {
      refetchOnMount: false,
    },
  );

  const projectName = project?.name || projectId;

  const { logsType, needsDefaultResolution, setLogsType } = useLogsType({
    projectId,
  });

  const openGuardrailsDialog = () => setIsGuardrailsDialogOpened(true);

  return (
    <>
      <PageBodyScrollContainer>
        <PageBodyStickyContainer
          className="mb-4 mt-6 flex items-center justify-between"
          direction="horizontal"
        >
          <h1
            data-testid="traces-page-title"
            className="comet-title-xs truncate break-words"
          >
            {projectName}
          </h1>
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
        {project?.description && (
          <PageBodyStickyContainer
            className="-mt-3 mb-4 flex min-h-8 items-center justify-between"
            direction="horizontal"
          >
            <div className="text-muted-slate">{project.description}</div>
          </PageBodyStickyContainer>
        )}
        {needsDefaultResolution ? (
          <Loader />
        ) : (
          <LogsTab
            projectId={projectId}
            projectName={projectName}
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
