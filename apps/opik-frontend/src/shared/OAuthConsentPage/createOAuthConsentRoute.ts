import { lazy } from "react";
import { AnyRoute, createRoute } from "@tanstack/react-router";

const OAuthConsentPage = lazy(() => import("./OAuthConsentPage"));

// Single source of truth for the pre-workspace MCP OAuth consent route. Both the v1 and v2
// routers mount it at the app root because WorkspaceVersionGate picks the router before any
// workspace is resolved and the consent URL is workspace-version-agnostic.
export const createOAuthConsentRoute = <T extends AnyRoute>(rootRoute: T) =>
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/oauth/consent",
    component: OAuthConsentPage,
  });
