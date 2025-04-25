import React from "react";
import { JsonParam, useQueryParam } from "use-query-params";

const CompareOptimizationsPage: React.FunctionComponent = () => {
  const [optimizationsIds = []] = useQueryParam("optimizations", JsonParam, {
    updateType: "replaceIn",
  });

  return <div>Optimization {optimizationsIds.toString()}</div>;
};

export default CompareOptimizationsPage;
