import { lazy } from "react";
import { AnyRoute, createRoute } from "@tanstack/react-router";

const OAuthConsentPage = lazy(() => import("./OAuthConsentPage"));

// Single source of truth for the pre-workspace MCP OAuth consent route. The router mounts it
// at the app root because the consent URL is reached before any workspace is resolved.
export const createOAuthConsentRoute = <T extends AnyRoute>(rootRoute: T) =>
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/oauth/consent",
    component: OAuthConsentPage,
  });
