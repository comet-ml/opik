import { Link, useRouterState } from "@tanstack/react-router";
import get from "lodash/get";
import { ReactElement } from "react";

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";

const Breadcrumbs = () => {
  const params = useBreadcrumbsStore((state) => state.params);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const breadcrumbs = useRouterState({
    select: (state) => {
      return state.matches
        .map((match) => {
          const title = (match.staticData as { title?: string }).title;
          const param = (match.staticData as { param?: string }).param;
          const paramValue = param
            ? get(match.params, [param], undefined)
            : undefined;

          const paramTitle = paramValue
            ? get(params, [param, paramValue], paramValue)
            : "";

          return {
            title: title || paramTitle,
            path: match.pathname,
          };
        })

        .filter((crumb) => Boolean(crumb.title));
    },
  });

  const renderBreadcrumbs = () => {
    const items: ReactElement[] = [];

    breadcrumbs.forEach((breadcrumb, index, all) => {
      items.push(
        <BreadcrumbItem key={breadcrumb.path}>
          <BreadcrumbLink asChild>
            <Link to={breadcrumb.path}>{breadcrumb.title as string}</Link>
          </BreadcrumbLink>
        </BreadcrumbItem>,
      );

      if (all.length - 1 !== index) {
        items.push(
          <BreadcrumbSeparator key={`separator-${breadcrumb.path}`} />,
        );
      }
    });

    return items;
  };

  const homeName = workspaceName === "default" ? "Personal" : workspaceName;

  return (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link className="pl-0.5" to="/">
              {homeName}
            </Link>
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbSeparator />
        {renderBreadcrumbs()}
      </BreadcrumbList>
    </Breadcrumb>
  );
};

export default Breadcrumbs;
