import { useEffect, useRef } from "react";
import { useNavigate, useParams } from "@tanstack/react-router";
import { useActiveWorkspaceName, useActiveProjectId } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

const FALLBACK_TIMEOUT_MS = 3000;

type V1CompatRedirectProps = {
  toPath: string;
};

const V1CompatRedirect = ({ toPath }: V1CompatRedirectProps) => {
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const params = useParams({ strict: false });
  const splat = (params as Record<string, string>)["_splat"] ?? "";
  const fallbackFired = useRef(false);

  useEffect(() => {
    if (activeProjectId) {
      const suffix = splat ? `/${splat}` : "";
      const target = `/${workspaceName}/projects/${activeProjectId}${toPath}${suffix}`;
      navigate({ to: target, replace: true });
      return;
    }

    const timer = setTimeout(() => {
      if (!fallbackFired.current) {
        fallbackFired.current = true;
        navigate({
          to: "/$workspaceName/projects",
          params: { workspaceName },
          replace: true,
        });
      }
    }, FALLBACK_TIMEOUT_MS);

    return () => clearTimeout(timer);
  }, [activeProjectId, workspaceName, toPath, splat, navigate]);

  return <Loader />;
};

export default V1CompatRedirect;
