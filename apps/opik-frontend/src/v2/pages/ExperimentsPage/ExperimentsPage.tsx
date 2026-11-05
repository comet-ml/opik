import React, { useCallback, useRef, useState } from "react";

import { Plus } from "lucide-react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
  useQueryParams,
  withDefault,
} from "use-query-params";

import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { EVALUATION_METHOD } from "@/types/datasets";
import { useActiveProjectId } from "@/store/AppStore";
import AddExperimentDialog from "@/v2/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import { buildDocsUrl } from "@/v2/lib/utils";
import { usePermissions } from "@/contexts/PermissionsContext";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import emptyExperimentsLightUrl from "/images/empty-experiments-light.svg";
import emptyExperimentsDarkUrl from "/images/empty-experiments-dark.svg";

const TAB_EVALUATION_METHOD: Record<string, EVALUATION_METHOD> = {
  test_suites: EVALUATION_METHOD.TEST_SUITE,
  datasets: EVALUATION_METHOD.DATASET,
};
const DEFAULT_TAB = "test_suites";

const ExperimentsPage: React.FC = () => {
  const activeProjectId = useActiveProjectId();
  const resetDialogKeyRef = useRef(0);
  const [query] = useQueryParam("new", JsonParam);
  // The tabs share the table's URL params, and the URL wins over each tab's
  // saved state, so switching tabs clears them to let the new tab load its own.
  const [{ tab }, setTabQuery] = useQueryParams(
    {
      tab: withDefault(StringParam, DEFAULT_TAB),
      search: StringParam,
      filters: JsonParam,
      page: NumberParam,
      groups: JsonParam,
      limits: JsonParam,
    },
    { updateType: "replaceIn" },
  );
  const activeTab = TAB_EVALUATION_METHOD[tab] ? tab : DEFAULT_TAB;

  const handleTabChange = useCallback(
    (value: string) => {
      setTabQuery({
        tab: value,
        search: undefined,
        filters: undefined,
        page: undefined,
        groups: undefined,
        limits: undefined,
      });
    },
    [setTabQuery],
  );

  const [openDialog, setOpenDialog] = useState<boolean>(
    Boolean(query?.experiment),
  );

  const {
    permissions: { canCreateExperiments },
  } = usePermissions();

  const { data: existenceData, isPending: isExistencePending } =
    useExperimentsList(
      {
        projectId: activeProjectId ?? undefined,
        page: 1,
        size: 1,
      },
      {
        enabled: !!activeProjectId,
      },
    );
  const isEmpty = !isExistencePending && (existenceData?.total ?? 0) === 0;

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  return (
    <PageBodyScrollContainer>
      <div className="flex min-h-full w-fit min-w-full flex-col pt-4">
        <PageBodyStickyContainer
          className="flex items-center justify-between pb-1"
          direction="horizontal"
          limitWidth
        >
          <h1 className="comet-body-accented truncate break-words">
            Experiments
          </h1>
          {canCreateExperiments && (
            <Button
              variant="default"
              size="xs"
              onClick={handleNewExperimentClick}
            >
              <Plus className="mr-1 size-4" />
              Create experiment
            </Button>
          )}
        </PageBodyStickyContainer>
        {isEmpty ? (
          <PageEmptyState
            lightImageUrl={emptyExperimentsLightUrl}
            darkImageUrl={emptyExperimentsDarkUrl}
            title="No experiments yet"
            description={
              "Get started by creating your first experiment.\nCompare prompts and models, evaluate results, and track performance over time."
            }
            primaryActionLabel={
              canCreateExperiments ? "Create your first experiment" : undefined
            }
            onPrimaryAction={
              canCreateExperiments ? handleNewExperimentClick : undefined
            }
            docsUrl={buildDocsUrl("/evaluation/overview")}
          />
        ) : (
          <>
            <PageBodyStickyContainer
              className="pb-1 pt-3"
              direction="horizontal"
              limitWidth
            >
              <Tabs value={activeTab} onValueChange={handleTabChange}>
                <TabsList variant="segmented-primary" className="w-fit">
                  <TabsTrigger variant="segmented-primary" value="test_suites">
                    Test suites
                  </TabsTrigger>
                  <TabsTrigger variant="segmented-primary" value="datasets">
                    Datasets
                  </TabsTrigger>
                </TabsList>
              </Tabs>
            </PageBodyStickyContainer>
            <GeneralDatasetsTab
              key={activeTab}
              evaluationMethod={TAB_EVALUATION_METHOD[activeTab]}
              isExistencePending={isExistencePending}
            />
          </>
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
