import type { AnyRouter } from "@tanstack/react-router";

declare module "@tanstack/react-router" {
  interface StaticDataRouteOption {
    hideUpgradeButton?: boolean;
    title?: string;
    param?: string;
    paramValue?: string;
  }
  interface Register {
    router: AnyRouter;
  }
}
