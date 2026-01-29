import React, { useCallback, useState } from "react";
import { ChevronDown, ChevronRight, Copy, FlaskConical } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import Loader from "@/components/shared/Loader/Loader";
import useConfigVariables, {
  ConfigExperiment,
} from "@/api/config/useConfigVariables";
import { formatDate } from "@/lib/date";
import { useToast } from "@/components/ui/use-toast";
import ExperimentCreatedPanel from "../ConfigurationTab/ExperimentCreatedPanel";

type ExperimentsTabProps = {
  projectId: string;
};

const ExperimentsTab: React.FC<ExperimentsTabProps> = () => {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selectedExperimentId, setSelectedExperimentId] = useState<
    string | null
  >(null);
  const { toast } = useToast();

  const { data: configData, isPending, isError } = useConfigVariables();
  const experiments = configData?.experiments ?? [];

  const toggleExpanded = useCallback((id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const handleCopyId = useCallback(
    (e: React.MouseEvent, id: string) => {
      e.stopPropagation();
      navigator.clipboard.writeText(id);
      toast({ description: "Experiment ID copied" });
    },
    [toast],
  );

  const handleBack = useCallback(() => {
    setSelectedExperimentId(null);
  }, []);

  if (isPending) {
    return (
      <PageBodyStickyContainer
        direction="horizontal"
        limitWidth
        className="pt-6"
      >
        <Loader />
      </PageBodyStickyContainer>
    );
  }

  if (isError) {
    return (
      <PageBodyStickyContainer
        direction="horizontal"
        limitWidth
        className="pt-6"
      >
        <DataTableNoData title="Backend not available">
          Start the config backend with: uv run python -m opik_config
        </DataTableNoData>
      </PageBodyStickyContainer>
    );
  }

  if (selectedExperimentId) {
    return (
      <PageBodyStickyContainer
        direction="horizontal"
        limitWidth
        className="pt-6"
      >
        <div className="rounded-lg border">
          <ExperimentCreatedPanel
            experimentId={selectedExperimentId}
            onBack={handleBack}
          />
        </div>
      </PageBodyStickyContainer>
    );
  }

  if (experiments.length === 0) {
    return (
      <PageBodyStickyContainer
        direction="horizontal"
        limitWidth
        className="pt-6"
      >
        <DataTableNoData title="No experiments yet">
          Create experiments from the Configuration tab by selecting variables
          and clicking "Create Experiment".
        </DataTableNoData>
      </PageBodyStickyContainer>
    );
  }

  return (
    <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
      <div className="mb-4 flex items-center gap-2">
        <FlaskConical className="size-5 text-muted-slate" />
        <h2 className="text-lg font-medium">
          {experiments.length} Experiment{experiments.length > 1 ? "s" : ""}
        </h2>
      </div>
      <div className="space-y-3">
        {experiments.map((experiment) => (
          <ExperimentCard
            key={experiment.id}
            experiment={experiment}
            isExpanded={expandedIds.has(experiment.id)}
            onToggle={() => toggleExpanded(experiment.id)}
            onCopyId={handleCopyId}
            onViewInstructions={() => setSelectedExperimentId(experiment.id)}
          />
        ))}
      </div>
    </PageBodyStickyContainer>
  );
};

type ExperimentCardProps = {
  experiment: ConfigExperiment;
  isExpanded: boolean;
  onToggle: () => void;
  onCopyId: (e: React.MouseEvent, id: string) => void;
  onViewInstructions: () => void;
};

const ExperimentCard: React.FC<ExperimentCardProps> = ({
  experiment,
  isExpanded,
  onToggle,
  onCopyId,
  onViewInstructions,
}) => {
  return (
    <div className="rounded-lg border bg-background">
      <button
        className="flex w-full items-center justify-between p-4 text-left hover:bg-muted/30"
        onClick={onToggle}
      >
        <div className="flex items-center gap-3">
          {isExpanded ? (
            <ChevronDown className="size-4 text-muted-slate" />
          ) : (
            <ChevronRight className="size-4 text-muted-slate" />
          )}
          <code className="font-mono text-sm">{experiment.id}</code>
          <Tag variant="gray" size="sm">
            {experiment.overrides.length} override
            {experiment.overrides.length > 1 ? "s" : ""}
          </Tag>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-muted-slate">
            {formatDate(experiment.createdAt)}
          </span>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={(e) => onCopyId(e, experiment.id)}
          >
            <Copy className="size-4" />
          </Button>
        </div>
      </button>
      {isExpanded && (
        <div className="border-t px-4 py-4">
          <div className="mb-4 space-y-2">
            {experiment.overrides.map((override) => (
              <div
                key={override.key}
                className="flex items-center justify-between rounded bg-muted/30 px-3 py-2"
              >
                <span className="font-mono text-sm">{override.key}</span>
                <div className="flex items-center gap-2">
                  <Tag variant="gray" size="sm" className="capitalize">
                    {override.type}
                  </Tag>
                  <Tag variant="purple" size="sm" className="max-w-[200px] truncate">
                    {String(override.value)}
                  </Tag>
                </div>
              </div>
            ))}
          </div>
          <Button
            size="sm"
            variant="outline"
            className="w-full"
            onClick={onViewInstructions}
          >
            View Instructions
          </Button>
        </div>
      )}
    </div>
  );
};

export default ExperimentsTab;
