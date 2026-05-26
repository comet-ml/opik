import React from "react";
import type { Period } from "@/ui/time-picker-utils";

export type SimpleTime = { hour: number; minute: number };

export interface Slot {
  date: Date | null;
  dateText: string;
  dateTouched: boolean;
  time: SimpleTime | null;
  timeText: string;
  timeTouched: boolean;
  period: Period;
}

export type SlotPatch = Partial<Slot>;

export const EMPTY_SLOT: Slot = {
  date: null,
  dateText: "",
  dateTouched: false,
  time: null,
  timeText: "",
  timeTouched: false,
  period: "AM",
};

export const flushOnEnter = (event: React.KeyboardEvent<HTMLInputElement>) => {
  if (event.key !== "Enter") return;
  const target = event.currentTarget;
  target.blur();
  target.focus();
};
