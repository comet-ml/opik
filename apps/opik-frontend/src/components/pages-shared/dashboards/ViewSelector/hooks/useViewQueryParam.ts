import { StringParam, useQueryParam } from "use-query-params";
import { VIEW_TYPE } from "../ViewSelector";

const useViewQueryParam = () => {
  const [rawView = VIEW_TYPE.DETAILS, setView] = useQueryParam(
    "view",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const view =
    rawView === VIEW_TYPE.DETAILS || rawView === VIEW_TYPE.DASHBOARDS
      ? rawView
      : VIEW_TYPE.DETAILS;

  return { view, setView };
};

export default useViewQueryParam;
