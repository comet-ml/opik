import { createRoute, type AnyRoute } from "@tanstack/react-router";
import V1CompatRedirect from "@/v2/redirect/V1CompatRedirect";

type V1RedirectEntry = {
  from: string;
  to: string;
  catchAll?: boolean;
};

const V1_REDIRECT_PATHS: V1RedirectEntry[] = [
  { from: "/experiments", to: "/experiments", catchAll: true },
  { from: "/evaluation-suites", to: "/evaluation-suites", catchAll: true },
  { from: "/datasets", to: "/evaluation-suites", catchAll: true },
  { from: "/prompts", to: "/prompts", catchAll: true },
  { from: "/playground", to: "/playground" },
  { from: "/optimizations", to: "/optimizations", catchAll: true },
  { from: "/online-evaluation", to: "/online-evaluation" },
  { from: "/annotation-queues", to: "/annotation-queues", catchAll: true },
  { from: "/alerts", to: "/alerts", catchAll: true },
];

export function createV1RedirectRoutes(parentRoute: AnyRoute) {
  return V1_REDIRECT_PATHS.map(({ from, to, catchAll }) => {
    const component = () => <V1CompatRedirect toPath={to} />;

    if (!catchAll) {
      return createRoute({
        path: from,
        getParentRoute: () => parentRoute,
        component,
      });
    }

    const base = createRoute({
      path: from,
      getParentRoute: () => parentRoute,
    });

    const indexRoute = createRoute({
      path: "/",
      getParentRoute: () => base,
      component,
    });

    const splatRoute = createRoute({
      path: "$",
      getParentRoute: () => base,
      component,
    });

    return base.addChildren([indexRoute, splatRoute]);
  });
}
