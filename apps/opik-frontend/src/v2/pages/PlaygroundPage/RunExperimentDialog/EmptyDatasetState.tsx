import React, { useState } from "react";

import { Button } from "@/ui/button";
import AddEditDatasetDialog from "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialog";
import AddEditTestSuiteDialog from "@/v2/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog";
import { DATASET_TYPE } from "@/types/datasets";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import emptyDatasetOrSuiteLightUrl from "/images/empty-dataset-or-suite-light.svg";
import emptyDatasetOrSuiteDarkUrl from "/images/empty-dataset-or-suite-dark.svg";

interface EmptyDatasetStateProps {
  type: DATASET_TYPE;
  onCreated: (dataset: { id: string }) => void;
  canCreate: boolean;
}

const COPY = {
  [DATASET_TYPE.DATASET]: {
    title: "No datasets yet",
    cta: "Create dataset",
  },
  [DATASET_TYPE.TEST_SUITE]: {
    title: "No test suites yet",
    cta: "Create test suite",
  },
} as const;

const EmptyDatasetState: React.FC<EmptyDatasetStateProps> = ({
  type,
  onCreated,
  canCreate,
}) => {
  const [open, setOpen] = useState(false);
  const copy = COPY[type];
  const { themeMode } = useTheme();
  const imageUrl =
    themeMode === THEME_MODE.DARK
      ? emptyDatasetOrSuiteDarkUrl
      : emptyDatasetOrSuiteLightUrl;

  return (
    <>
      <div className="flex min-h-[160px] flex-col items-center justify-center gap-1 px-4 py-2 text-center">
        <img src={imageUrl} alt="" className="h-8 w-7 shrink-0" />
        <div className="comet-body-xs-accented pb-1 text-foreground">
          {copy.title}
        </div>
        {canCreate && (
          <Button
            variant="tableLink"
            size="2xs"
            className="h-auto px-0"
            onClick={() => setOpen(true)}
          >
            {copy.cta}
          </Button>
        )}
      </div>
      {type === DATASET_TYPE.DATASET ? (
        <AddEditDatasetDialog
          open={open}
          setOpen={setOpen}
          onDatasetCreated={onCreated}
        />
      ) : (
        <AddEditTestSuiteDialog
          open={open}
          setOpen={setOpen}
          onDatasetCreated={onCreated}
        />
      )}
    </>
  );
};

export default EmptyDatasetState;
