import { createContext, useContext } from "react";
import { DateRangePreset, DateRangeValue } from "./types";

type DateRangeSelectContextType = {
  setCustomRange: (range: DateRangeValue) => void;
  selectValue: DateRangePreset | "custom";
  value: DateRangeValue;
  minDate?: Date;
  maxDate?: Date;
};

export const DateRangeSelectContext =
  createContext<DateRangeSelectContextType | null>(null);

export const useDateRangeSelectContext = () => {
  const context = useContext(DateRangeSelectContext);
  if (!context) {
    throw new Error(
      "DateRangeSelect components must be used within DateRangeSelect.Provider",
    );
  }
  return context;
};
