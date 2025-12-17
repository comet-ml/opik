import { useMemo, useRef } from "react";
import pick from "lodash/pick";
import isNull from "lodash/isNull";

import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";
import { getProviderDisplayName, getProviderIcon } from "@/lib/provider";

interface ModelOption {
  label: string;
  value: string;
}

interface FreeModelOption extends ModelOption {
  composedProviderType: COMPOSED_PROVIDER_TYPE;
  icon: ReturnType<typeof getProviderIcon>;
}

interface GroupOption {
  label: string;
  options: ModelOption[];
  icon: ReturnType<typeof getProviderIcon>;
  composedProviderType: COMPOSED_PROVIDER_TYPE;
}

interface UseModelOptionsResult {
  freeModelOption: FreeModelOption | null;
  groupOptions: GroupOption[];
  filteredFreeModel: FreeModelOption | null;
  filteredGroups: GroupOption[];
  modelProviderMapRef: React.MutableRefObject<
    Record<string, COMPOSED_PROVIDER_TYPE>
  >;
}

export function useModelOptions(
  configuredProvidersList: ProviderObject[],
  getProviderModels: () => Record<string, ModelOption[]>,
  filterValue: string,
): UseModelOptionsResult {
  const modelProviderMapRef = useRef<Record<string, COMPOSED_PROVIDER_TYPE>>(
    {},
  );

  const freeModelOption = useMemo(() => {
    const freeProvider = configuredProvidersList.find(
      (p) => p.provider === PROVIDER_TYPE.OPIK_FREE,
    );

    if (!freeProvider) return null;

    const providerModels = getProviderModels()[PROVIDER_TYPE.OPIK_FREE];
    if (!providerModels?.length) return null;

    const model = providerModels[0];
    const configModelLabel = freeProvider.configuration?.model_label?.trim();
    const modelLabel = configModelLabel || model.label;

    modelProviderMapRef.current[model.value] = PROVIDER_TYPE.OPIK_FREE;

    return {
      label: `${modelLabel} (free)`,
      value: model.value,
      composedProviderType: PROVIDER_TYPE.OPIK_FREE as COMPOSED_PROVIDER_TYPE,
      icon: getProviderIcon(freeProvider),
    };
  }, [configuredProvidersList, getProviderModels]);

  const groupOptions = useMemo(() => {
    const filteredByConfiguredProviders = pick(
      getProviderModels(),
      configuredProvidersList
        .filter((p) => p.provider !== PROVIDER_TYPE.OPIK_FREE)
        .map((p) => p.ui_composed_provider),
    );

    Object.entries(filteredByConfiguredProviders).forEach(
      ([pn, providerModels]) => {
        providerModels.forEach(({ value }) => {
          modelProviderMapRef.current[value] = pn;
        });
      },
    );

    return Object.entries(filteredByConfiguredProviders)
      .map(([pn, providerModels]) => {
        const composedProviderType = pn as COMPOSED_PROVIDER_TYPE;
        const configuredProvider = configuredProvidersList.find(
          (p) => p.ui_composed_provider === composedProviderType,
        )!;

        const options = providerModels.map((providerModel) => ({
          label: providerModel.label,
          value: providerModel.value,
        }));

        if (!options.length) return null;

        return {
          label: getProviderDisplayName(configuredProvider),
          options,
          icon: getProviderIcon(configuredProvider),
          composedProviderType,
        };
      })
      .filter((g): g is NonNullable<typeof g> => !isNull(g));
  }, [configuredProvidersList, getProviderModels]);

  // Combined filtering logic
  const { filteredFreeModel, filteredGroups } = useMemo(() => {
    const lowered = filterValue.toLowerCase();
    const matchesFilter = (label: string, value: string) =>
      filterValue === "" ||
      label.toLowerCase().includes(lowered) ||
      value.toLowerCase().includes(lowered);

    return {
      filteredFreeModel:
        freeModelOption &&
        matchesFilter(freeModelOption.label, freeModelOption.value)
          ? freeModelOption
          : null,
      filteredGroups: groupOptions
        .map((g) => ({
          ...g,
          options: g.options.filter((o) => matchesFilter(o.label, o.value)),
        }))
        .filter((g) => filterValue === "" || g.options.length > 0),
    };
  }, [filterValue, freeModelOption, groupOptions]);

  return {
    freeModelOption,
    groupOptions,
    filteredFreeModel,
    filteredGroups,
    modelProviderMapRef,
  };
}
