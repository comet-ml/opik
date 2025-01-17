import React from "react";
import { useSearch } from "@tanstack/react-router";

import useRulesLogsList from "@/api/automations/useRulesLogsList";
import NoData from "@/components/shared/NoData/NoData";
import Loader from "@/components/shared/Loader/Loader";

const AutomationLogsPage = () => {
  const {
    rule_id,
    project_id,
  }: {
    project_id?: string;
    rule_id?: string;
  } = useSearch({ strict: false });

  const { data, isPending } = useRulesLogsList(
    {
      projectId: project_id!,
      ruleId: rule_id!,
    },
    {
      enabled: Boolean(rule_id) && Boolean(project_id),
    },
  );

  const items = data?.content ?? [];

  if (!rule_id || !project_id) {
    return <NoData message="No project and/or rule parameters set."></NoData>;
  }

  if (isPending) {
    return <Loader />;
  }

  if (items.length === 0) {
    return <NoData message="There are no logs for this rule."></NoData>;
  }

  return (
    <div className="comet-code px-4 py-2">
      {items.map((item) => (
        <div
          className="whitespace-pre-wrap"
          key={item.timestamp + item.level}
        >{`${item.timestamp} ${item.level} ${item.message}`}</div>
      ))}
    </div>
  );
};

export default AutomationLogsPage;
