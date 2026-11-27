export type UndoHistory<T> = {
  past: T[];
  present: T;
  future: T[];
};

export const createHistory = <T>(present: T): UndoHistory<T> => ({
  past: [],
  present,
  future: [],
});

export const commit = <T>(
  { past, present }: UndoHistory<T>,
  next: T,
): UndoHistory<T> => ({
  past: [...past, present],
  present: next,
  future: [],
});

export const replacePresent = <T>(
  history: UndoHistory<T>,
  next: T,
): UndoHistory<T> => ({ ...history, present: next });

export const undo = <T>(history: UndoHistory<T>): UndoHistory<T> => {
  const { past, present, future } = history;
  if (past.length === 0) return history;
  return {
    past: past.slice(0, -1),
    present: past[past.length - 1],
    future: [present, ...future],
  };
};

export const redo = <T>(history: UndoHistory<T>): UndoHistory<T> => {
  const { past, present, future } = history;
  if (future.length === 0) return history;
  return {
    past: [...past, present],
    present: future[0],
    future: future.slice(1),
  };
};
