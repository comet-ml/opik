import { useEffect } from "react";
import { useLocation, useNavigate, useParams } from "@tanstack/react-router";

// Project-scoped pages load their entity by id, so the URL can name a project
// the entity does not belong to (e.g. links built from the active project).
// Move to the owning project so the page agrees with that project's lists.
// Entities without a project predate project scoping and are left in place.
export const useRedirectToOwnProject = (ownProjectId?: string | null) => {
  const navigate = useNavigate();
  const { projectId } = useParams({ strict: false }) as { projectId?: string };
  const location = useLocation();

  useEffect(() => {
    if (!ownProjectId || !projectId || ownProjectId === projectId) return;

    navigate({
      to: location.pathname.replace(
        `/projects/${projectId}/`,
        `/projects/${ownProjectId}/`,
      ),
      search: location.search as Record<string, unknown>,
      replace: true,
    });
  }, [ownProjectId, projectId, location.pathname, location.search, navigate]);
};
