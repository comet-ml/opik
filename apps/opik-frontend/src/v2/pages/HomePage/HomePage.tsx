import { useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  useActiveWorkspaceName,
  useActiveProjectId,
  useIsProjectLoading,
} from "@/store/AppStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import Loader from "@/shared/Loader/Loader";

const HomePage = () => {
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const isLoading = useIsProjectLoading();
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const navigate = useNavigate();

  useEffect(() => {
    if (isLoading) return;

    if (activeProjectId) {
      navigate({
        to: ollieEnabled
          ? "/$workspaceName/projects/$projectId/home"
          : "/$workspaceName/projects/$projectId/logs",
        params: { workspaceName, projectId: activeProjectId },
        replace: true,
      });
    } else {
      navigate({
        to: "/$workspaceName/projects",
        params: { workspaceName },
        replace: true,
      });
    }
  }, [activeProjectId, isLoading, workspaceName, ollieEnabled, navigate]);

  return <Loader />;
};

export default HomePage;
