import { useQueryParam } from "use-query-params";
import { DetailsActionSectionParam } from "./types";

export const useDetailsActionSectionState = (name: string) => {
  const [detailsActionSection = null, setDetailsActionSection] = useQueryParam(
    name,
    DetailsActionSectionParam,
    {
      updateType: "replaceIn",
    },
  );

  return [detailsActionSection, setDetailsActionSection] as const;
};
