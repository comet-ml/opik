import { useEffect, useRef } from "react";
import { useNavigate, useParams, useRouterState } from "@tanstack/react-router";
import { useActiveWorkspaceName } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

// Maps old ?tab= values to project-scoped route suffixes
const TAB_ROUTE_MAP: Record<string, string> = {
  "annotation-queues": "/annotation-queues",
  rules: "/online-evaluation",
  configuration: "/agent-configuration",
  insights: "/dashboards",
  metrics: "/dashboards",
};

const TracesTabRedirect = () => {
  const workspaceName = useActiveWorkspaceName();
  const { projectId } = useParams({ strict: false }) as {
    projectId?: string;
  };
  const navigate = useNavigate();
  const search = useRouterState({
    select: (s) => s.location.search as Record<string, string>,
  });
  const hasRedirected = useRef(false);

  useEffect(() => {
    if (!projectId || hasRedirected.current) return;

    const tab = search.tab;
    const legacyType = search.type;
    const legacyView = search.view;

    // Strip tab/type/view, forward remaining params to target route
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { tab: _t, type: _ty, view: _v, ...forwardParams } = search;

    const navigateToProjectRoute = (
      suffix: string,
      extraSearch?: Record<string, string>,
    ) => {
      const mergedSearch = { ...forwardParams, ...extraSearch };
      hasRedirected.current = true;
      navigate({
        to: `/$workspaceName/projects/$projectId${suffix}`,
        params: { workspaceName, projectId },
        search: Object.keys(mergedSearch).length > 0 ? mergedSearch : undefined,
        replace: true,
      });
    };

    // ?tab= takes priority
    if (tab && TAB_ROUTE_MAP[tab]) {
      navigateToProjectRoute(TAB_ROUTE_MAP[tab]);
      return;
    }

    // Legacy ?type= param (same mapping as ?tab=)
    if (legacyType && TAB_ROUTE_MAP[legacyType]) {
      navigateToProjectRoute(TAB_ROUTE_MAP[legacyType]);
      return;
    }

    // Legacy ?view=dashboards → dashboards
    if (legacyView === "dashboards") {
      navigateToProjectRoute("/dashboards");
      return;
    }

    // Default: redirect to /logs
    const logsType = forwardParams.logsType ?? legacyType;
    navigateToProjectRoute("/logs", logsType ? { logsType } : undefined);
  }, [workspaceName, projectId, search, navigate]);

  return <Loader />;
};

export default TracesTabRedirect;
