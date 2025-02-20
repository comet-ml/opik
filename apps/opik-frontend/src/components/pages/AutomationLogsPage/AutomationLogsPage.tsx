import React from "react";
import { useSearch } from "@tanstack/react-router";

import useRulesLogsList from "@/api/automations/useRulesLogsList";
import NoData from "@/components/shared/NoData/NoData";
import Loader from "@/components/shared/Loader/Loader";

const AutomationLogsPage = () => {
  const {
    rule_id,
  }: {
    rule_id?: string;
  } = useSearch({ strict: false });

  const { data, isPending } = useRulesLogsList(
    {
      ruleId: rule_id!,
    },
    {
      enabled: Boolean(rule_id),
    },
  );

  const items = data?.content ?? [];

  if (!rule_id) {
    return <NoData message="No rule parameters set."></NoData>;
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
