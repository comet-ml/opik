import React from "react";
import { ExternalLink } from "lucide-react";

import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { Button } from "@/ui/button";
import { CreateDatasetMode } from "@/v2/pages-shared/datasets/CreateDatasetSidebar/CreateDatasetSidebar";
import { CREATE_ENTITY_OPTIONS } from "@/v2/pages-shared/datasets/CreateEntityMenu";

type DatasetEmptyStateProps = {
  title: string;
  description: string;
  lightImageUrl: string;
  darkImageUrl: string;
  docsUrl: string;
  canCreate: boolean;
  onSelect: (mode: CreateDatasetMode) => void;
};

const DatasetEmptyState: React.FC<DatasetEmptyStateProps> = ({
  title,
  description,
  lightImageUrl,
  darkImageUrl,
  docsUrl,
  canCreate,
  onSelect,
}) => {
  const { themeMode } = useTheme();
  const imageUrl = themeMode === THEME_MODE.DARK ? darkImageUrl : lightImageUrl;

  return (
    <div className="flex flex-1 items-center justify-center gap-10 py-10">
      <div className="flex w-full max-w-[540px] flex-col gap-6">
        <div className="flex flex-col gap-2">
          <h2 className="comet-title-s text-foreground">{title}</h2>
          <p className="comet-body-s whitespace-pre-line text-muted-slate">
            {description}
          </p>
        </div>
        {canCreate && (
          <div className="flex flex-col gap-3">
            {CREATE_ENTITY_OPTIONS.map(
              ({
                mode,
                Icon,
                iconClassName,
                title: optionTitle,
                description: optionDescription,
              }) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => onSelect(mode)}
                  className="flex flex-col gap-1 rounded-lg border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-[#F3F4FE] dark:hover:bg-primary-foreground"
                >
                  <div className="flex items-center gap-2">
                    <Icon className={`size-4 shrink-0 ${iconClassName}`} />
                    <span className="comet-body-s-accented">{optionTitle}</span>
                  </div>
                  <span className="comet-body-xs text-light-slate">
                    {optionDescription}
                  </span>
                </button>
              ),
            )}
          </div>
        )}
        <Button variant="outline" size="sm" className="self-start" asChild>
          <a href={docsUrl} target="_blank" rel="noreferrer">
            View docs
            <ExternalLink className="ml-1.5 size-3.5" />
          </a>
        </Button>
      </div>
      <img
        src={imageUrl}
        alt={title}
        className="hidden max-w-[420px] lg:block"
      />
    </div>
  );
};

export default DatasetEmptyState;
