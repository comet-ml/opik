import { useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  useActiveWorkspaceName,
  useActiveProjectId,
  useIsProjectLoading,
} from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

const HomePage = () => {
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const isLoading = useIsProjectLoading();
  const navigate = useNavigate();

  useEffect(() => {
    if (isLoading) return;

    if (activeProjectId) {
      navigate({
        to: "/$workspaceName/projects/$projectId/home",
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
  }, [activeProjectId, isLoading, workspaceName, navigate]);

  return <Loader />;
};

export default HomePage;
