import { ReactElement } from "react";
import get from "lodash/get";
import { Link, useRouterState } from "@tanstack/react-router";

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { calculateWorkspaceName } from "@/lib/utils";

type CustomRouteStaticData = {
  title?: string;
  param?: string;
  paramValue?: string;
};

const Breadcrumbs = () => {
  const params = useBreadcrumbsStore((state) => state.params);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
          };
        })

        .filter((crumb) => Boolean(crumb.title));
    },
  });

  const renderBreadcrumbs = () => {
    const items: ReactElement[] = [];

    breadcrumbs.forEach((breadcrumb, index, all) => {
      const isLast = all.length - 1 === index;

      items.push(
        <BreadcrumbItem key={breadcrumb.path}>
          {isLast ? (
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

  return (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          <BreadcrumbLink asChild>
            <Link
              className="pl-0.5"
              to="/$workspaceName"
              params={{ workspaceName }}
            >
              {calculateWorkspaceName(workspaceName)}
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
