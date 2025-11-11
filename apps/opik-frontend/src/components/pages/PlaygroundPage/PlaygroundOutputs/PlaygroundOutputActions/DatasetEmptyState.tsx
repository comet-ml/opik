import React from "react";
import { useTranslation } from "react-i18next";

const DatasetEmptyState = () => {
  const { t } = useTranslation();
  
  return (
    <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
      <div className="comet-body-s-accented pb-1 text-foreground">
        {t("playground.datasetEmptyState.title")}
      </div>
      <div className="comet-body-s text-muted-slate">
        {t("playground.datasetEmptyState.description")}
      </div>
    </div>
  );
};

export default DatasetEmptyState;
