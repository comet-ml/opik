import React, { useCallback, useRef, useState } from "react";

import { Info } from "lucide-react";
import { JsonParam, useQueryParam } from "use-query-params";

import { Button } from "@/ui/button";
import { useActiveProjectId } from "@/store/AppStore";
import AddExperimentDialog from "@/v2/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";

const ExperimentsPage: React.FC = () => {
  const activeProjectId = useActiveProjectId();
  const resetDialogKeyRef = useRef(0);
  const [query] = useQueryParam("new", JsonParam);
  const [openDialog, setOpenDialog] = useState<boolean>(
    Boolean(query?.experiment),
  );

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="flex items-center justify-between pb-1 pt-4"
        direction="horizontal"
        limitWidth
      >
        <h1 className="comet-title-xs truncate break-words">Experiments</h1>
        <Button variant="outline" size="xs" onClick={handleNewExperimentClick}>
          <Info className="mr-1.5 size-3.5" />
          Create new experiment
        </Button>
      </PageBodyStickyContainer>
      <GeneralDatasetsTab onNewExperimentClick={handleNewExperimentClick} />
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        datasetName={query?.datasetName}
        projectId={activeProjectId}
      />
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
