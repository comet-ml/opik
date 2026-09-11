import { type AnyRoute } from "@tanstack/react-router";
import { type ComponentType } from "react";

export type PluginRouteParents = {
  workspaceGuard: AnyRoute;
};

export type PluginSidebarSection = {
  matches: (pathname: string) => boolean;
  Content: ComponentType<{ expanded: boolean }>;
};

export type PluginManifest = {
  name: string;
  routes?: (parents: PluginRouteParents) => AnyRoute[];
  sidebar?: PluginSidebarSection;
};

export const definePlugin = (manifest: PluginManifest): PluginManifest =>
  manifest;
