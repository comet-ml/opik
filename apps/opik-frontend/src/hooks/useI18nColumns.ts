/**
 * 通用的列翻译辅助 Hook
 * 用于简化表格列的国际化处理
 */
import { useTranslation } from "react-i18next";
import { useMemo } from "react";
import { ColumnData, COLUMN_TYPE } from "@/types/shared";

export const useI18nColumns = <T extends Record<string, any>>(
  columns: ColumnData<T>[],
  translationPrefix: string
): ColumnData<T>[] => {
  const { t } = useTranslation();

  return useMemo(() => {
    return columns.map((column) => ({
      ...column,
      label: typeof column.label === "string" && column.label.startsWith("t:")
        ? t(`${translationPrefix}.${column.label.slice(2)}`)
        : column.label,
    }));
  }, [columns, t, translationPrefix]);
};

/**
 * 常用的表格列翻译键映射
 */
export const COMMON_COLUMN_TRANSLATIONS = {
  id: "common.id",
  name: "common.name",
  description: "common.description",
  createdAt: "common.createdAt",
  createdBy: "common.createdBy",
  lastUpdated: "common.lastUpdated",
  actions: "common.actions",
};

