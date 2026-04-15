import React, { useCallback, useRef, useState } from "react";

import { Plus } from "lucide-react";
import { JsonParam, useQueryParam } from "use-query-params";

import { Button } from "@/ui/button";
import { useActiveProjectId } from "@/store/AppStore";
import AddExperimentDialog from "@/v2/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import { buildDocsUrl } from "@/lib/utils";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import emptyExperimentsLightUrl from "/images/empty-experiments-light.svg";
import emptyExperimentsDarkUrl from "/images/empty-experiments-dark.svg";

const ExperimentsPage: React.FC = () => {
  const activeProjectId = useActiveProjectId();
  const resetDialogKeyRef = useRef(0);
  const [query] = useQueryParam("new", JsonParam);
  const [openDialog, setOpenDialog] = useState<boolean>(
    Boolean(query?.experiment),
  );

  const { data: existenceData } = useExperimentsList(
    {
      projectId: activeProjectId ?? undefined,
      page: 1,
      size: 1,
    },
    {
      enabled: !!activeProjectId,
    },
  );
  const isEmpty = (existenceData?.total ?? 0) === 0;

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  return (
    <PageBodyScrollContainer>
      <div className="flex min-h-full flex-col pt-4">
        <PageBodyStickyContainer
          className="flex items-center justify-between pb-1"
          direction="horizontal"
          limitWidth
        >
          <h1 className="comet-body-accented truncate break-words">
            Experiments
          </h1>
          <Button
            variant="default"
            size="xs"
            onClick={handleNewExperimentClick}
          >
            <Plus className="mr-1 size-4" />
            Create experiment
          </Button>
        </PageBodyStickyContainer>
        {isEmpty ? (
          <PageEmptyState
            lightImageUrl={emptyExperimentsLightUrl}
            darkImageUrl={emptyExperimentsDarkUrl}
            title="No experiments yet"
            description={
              "Get started by creating your first experiment.\nCompare prompts and models, evaluate results, and track performance over time."
            }
            primaryActionLabel="Create your first experiment"
            onPrimaryAction={handleNewExperimentClick}
            docsUrl={buildDocsUrl("/evaluation/overview")}
          />
        ) : (
          <GeneralDatasetsTab onNewExperimentClick={handleNewExperimentClick} />
        )}
        <AddExperimentDialog
          key={resetDialogKeyRef.current}
          open={openDialog}
          setOpen={setOpenDialog}
          datasetName={query?.datasetName}
          projectId={activeProjectId}
        />
      </div>
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
