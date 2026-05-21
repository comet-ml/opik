import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";

export const formatBlueprintValue = (v: BlueprintValue): string => {
  switch (v.type) {
    case BlueprintValueType.INT:
    case BlueprintValueType.FLOAT: {
      return v.value;
    }
    case BlueprintValueType.BOOLEAN:
      return v.value === "true" ? "True" : "False";
    default:
      return v.value;
  }
};
