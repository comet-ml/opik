import React from "react";
import { ArrowUpRight } from "lucide-react";
import { useNavigate, useRouter } from "@tanstack/react-router";
import { StringParam, useQueryParam } from "use-query-params";

import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import { usePermissions } from "@/contexts/PermissionsContext";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { parseExperimentReturnHref } from "@/v2/pages-shared/datasets/DatasetItemsPage/parseExperimentReturnHref";

type ViewInExperimentButtonProps = {
  datasetItemId: string;
};

// Counterpart of the trace panel's "Experiment" button. An item has no single
// owning experiment, so this only appears when the user arrived from one via
// `from`, and reopens that view on the item currently shown in the panel.
const ViewInExperimentButton: React.FC<ViewInExperimentButtonProps> = ({
  datasetItemId,
}) => {
  const router = useRouter();
  const navigate = useNavigate();
  const [from] = useQueryParam("from", StringParam);
  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  const experimentReturn = parseExperimentReturnHref(from, router.basepath);
  const returnSearch: Record<string, unknown> = experimentReturn
    ? router.options.parseSearch(experimentReturn.searchStr)
    : {};
  const experimentsIds = Array.isArray(returnSearch.experiments)
    ? (returnSearch.experiments as string[])
    : [];

  const experimentNames = useExperimentsByIds({ experimentsIds })
    .map((response) => response.data?.name)
    .filter(Boolean);

  if (!experimentReturn || !experimentsIds.length || !canViewExperiments) {
    return null;
  }

  const label = experimentsIds.length > 1 ? "experiments" : "experiment";
  const tooltip = experimentNames.length
    ? `View this item in ${label}: ${experimentNames.join(", ")}`
    : `View this item in ${label}`;

  return (
    <TooltipWrapper content={tooltip}>
      <Button
        variant="outline"
        size="2xs"
        onClick={() =>
          navigate({
            to: experimentReturn.to,
            search: { ...returnSearch, row: datasetItemId },
          })
        }
      >
        Experiment
        <ArrowUpRight className="ml-1 size-3.5" />
      </Button>
    </TooltipWrapper>
  );
};

export default ViewInExperimentButton;
