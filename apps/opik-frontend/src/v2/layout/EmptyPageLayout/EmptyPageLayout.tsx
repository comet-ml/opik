import React from "react";
import { Outlet } from "@tanstack/react-router";

export const EmptyPageLayout = ({
  children = <Outlet />,
}: {
  children?: React.ReactNode;
}) => {
  return children;
};

export default EmptyPageLayout;
