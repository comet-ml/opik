export const advanceToDelay = (ms: number) => {
  const promise = delay(ms);
  vi.advanceTimersByTime(ms);
  return promise;
};

export const delay = (ms: number) => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};
