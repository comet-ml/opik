import { ReactElement } from "react";
import get from "lodash/get";
import { Link, useRouterState } from "@tanstack/react-router";

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbSeparator,
} from "@/ui/breadcrumb";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import usePluginsStore from "@/store/PluginsStore";
import useAppStore from "@/store/AppStore";
import { calculateWorkspaceName } from "@/lib/utils";
import ProjectBreadcrumbSelector from "@/v2/layout/Breadcrumbs/ProjectBreadcrumbSelector";

type CustomRouteStaticData = {
  title?: string;
  param?: string;
  paramValue?: string;
  hideRoot?: boolean;
};

const Breadcrumbs = () => {
  const params = useBreadcrumbsStore((state) => state.params);
  const WorkspaceSelectorComponent = usePluginsStore(
    (state) => state.WorkspaceSelector,
  );
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const hideRoot = useRouterState({
    select: (state) =>
      state.matches.some(
        (match) => (match.staticData as CustomRouteStaticData).hideRoot,
      ),
  });

  const breadcrumbs = useRouterState({
    select: (state) => {
      return state.matches
        .map((match) => {
          const {
            title,
            param,
            paramValue: staticParamValue,
          } = match.staticData as CustomRouteStaticData;

          const paramValue = param
            ? get(match.params, [param], staticParamValue)
            : undefined;

          const paramTitle = paramValue
            ? get(params, [param, paramValue], paramValue)
            : "";

          return {
            title: title || paramTitle,
            path: match.pathname,
            param,
            paramValue,
          };
        })

        .filter((crumb) => Boolean(crumb.title));
    },
  });

  const renderBreadcrumbs = () => {
    const items: ReactElement[] = [];

    breadcrumbs.forEach((breadcrumb, index, all) => {
      const isLast = all.length - 1 === index;
      const isProject =
        breadcrumb.param === "projectId" && Boolean(breadcrumb.paramValue);

      items.push(
        <BreadcrumbItem key={breadcrumb.path}>
          {isProject ? (
            <ProjectBreadcrumbSelector
              projectId={breadcrumb.paramValue as string}
              title={breadcrumb.title}
            />
          ) : isLast ? (
            <span className="cursor-default truncate">{breadcrumb.title}</span>
          ) : (
            <BreadcrumbLink asChild>
              <Link to={breadcrumb.path}>{breadcrumb.title}</Link>
            </BreadcrumbLink>
          )}
        </BreadcrumbItem>,
      );

      if (!isLast) {
        items.push(
          <BreadcrumbSeparator key={`separator-${breadcrumb.path}`} />,
        );
      }
    });

    return items;
  };

  const renderWorkspaceItem = () => {
    if (WorkspaceSelectorComponent) {
      return <WorkspaceSelectorComponent />;
    }

    return (
      <BreadcrumbLink asChild>
        <Link
          className="pl-0.5"
          to="/$workspaceName"
          params={{ workspaceName }}
        >
          {calculateWorkspaceName(workspaceName)}
        </Link>
      </BreadcrumbLink>
    );
  };

  return (
    <Breadcrumb>
      <BreadcrumbList>
        {!hideRoot && (
          <>
            <BreadcrumbItem>{renderWorkspaceItem()}</BreadcrumbItem>
            <BreadcrumbSeparator />
          </>
        )}
        {renderBreadcrumbs()}
      </BreadcrumbList>
    </Breadcrumb>
  );
};

export default Breadcrumbs;
