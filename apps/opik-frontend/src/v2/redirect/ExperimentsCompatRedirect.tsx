import { useLocation } from "@tanstack/react-router";
import isString from "lodash/isString";
import useExperimentById from "@/api/datasets/useExperimentById";
import Loader from "@/shared/Loader/Loader";
import V1CompatRedirect, {
  V1CompatRedirectProps,
} from "@/v2/redirect/V1CompatRedirect";

// Experiment links printed by the SDKs carry no project, and the compare page
// only shows experiments of the project in its URL, so resolve the project from
// the experiment itself instead of the active one.
const ExperimentsCompatRedirect = ({ toPath }: V1CompatRedirectProps) => {
  const location = useLocation();
  const experiments = (location.search as Record<string, unknown>).experiments;
  const experimentId =
    Array.isArray(experiments) && isString(experiments[0])
      ? experiments[0]
      : "";

  const { data: experiment, isLoading } = useExperimentById(
    { experimentId },
    { enabled: Boolean(experimentId), retry: false },
  );

  if (isLoading) {
    return <Loader />;
  }

  return (
    <V1CompatRedirect toPath={toPath} projectId={experiment?.project_id} />
  );
};

export default ExperimentsCompatRedirect;
