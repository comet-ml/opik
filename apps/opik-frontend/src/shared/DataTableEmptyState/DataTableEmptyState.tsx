import React from "react";
import { ExternalLink, type LucideIcon } from "lucide-react";
import { Button } from "@/ui/button";

type DataTableEmptyStateProps = {
  icon: LucideIcon;
  title: string;
  description: string;
  docsUrl: string;
  onQuickstartClick: () => void;
};

const DataTableEmptyState: React.FC<DataTableEmptyStateProps> = ({
  icon: Icon,
  title,
  description,
  docsUrl,
  onQuickstartClick,
}) => {
  return (
    <div className="flex h-[50vh] w-full items-center justify-center">
      <div className="flex flex-col items-center gap-4">
        <Icon className="size-8 text-lime-500" />
        <h2 className="comet-title-m">{title}</h2>
        <p className="comet-body-s max-w-[420px] text-center text-muted-slate">
          {description}
        </p>
        <div className="flex gap-2 pt-2">
          <Button variant="outline" asChild>
            <a href={docsUrl} target="_blank" rel="noreferrer">
              Read docs
              <ExternalLink className="ml-2 size-3.5" />
            </a>
          </Button>
          <Button onClick={onQuickstartClick}>Quickstart guide</Button>
        </div>
      </div>
    </div>
  );
};

export default DataTableEmptyState;
