import { useEffect } from "react";
import { useNavigate, useParams, useLocation } from "@tanstack/react-router";
import {
  useActiveWorkspaceName,
  useActiveProjectId,
  useIsProjectLoading,
} from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

type V1CompatRedirectProps = {
  toPath: string;
};

const V1CompatRedirect = ({ toPath }: V1CompatRedirectProps) => {
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const isLoading = useIsProjectLoading();
  const navigate = useNavigate();
  const params = useParams({ strict: false });
  const location = useLocation();
  const splat = (params as Record<string, string>)["_splat"] ?? "";

  useEffect(() => {
    if (isLoading) return;

    if (activeProjectId) {
      const suffix = splat ? `/${splat}` : "";
      const target = `/${workspaceName}/projects/${activeProjectId}${toPath}${suffix}`;
      navigate({
        to: target,
        search: location.search as Record<string, unknown>,
        replace: true,
      });
    } else {
      navigate({
        to: "/$workspaceName/projects",
        params: { workspaceName },
        replace: true,
      });
    }
  }, [
    activeProjectId,
    isLoading,
    workspaceName,
    toPath,
    splat,
    navigate,
    location.search,
  ]);

  return <Loader />;
};

export default V1CompatRedirect;
