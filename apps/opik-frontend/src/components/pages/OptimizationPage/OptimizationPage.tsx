import React from "react";
import { Navigate, useLocation } from "@tanstack/react-router";

const OptimizationPage = () => {
  const pathname = useLocation({
    select: (location) => location.pathname,
  });

  const path = pathname.split("/");
  const optimizations = [path[path.length - 1]];
  path.splice(-1, 1, "compare");

  return (
    <Navigate
      to={path.join("/")}
      search={{
        optimizations,
      }}
    />
  );
};

export default OptimizationPage;
