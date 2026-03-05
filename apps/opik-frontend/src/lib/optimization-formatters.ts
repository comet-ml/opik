export const formatAsPercentage = (value: number): string => {
  return `${Math.round(value * 100)}%`;
};

export const formatAsDuration = (seconds: number): string => {
  if (seconds < 1) {
    return `${Math.round(seconds * 1000)}ms`;
  }
  if (seconds < 60) {
    return `${Number(seconds.toFixed(1))}s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = Math.round(seconds % 60);
  return remainingSeconds > 0
    ? `${minutes}m ${remainingSeconds}s`
    : `${minutes}m`;
};

export const formatAsCurrency = (dollars: number): string => {
  if (dollars < 0.01) {
    return `$${dollars.toFixed(4)}`;
  }
  if (dollars < 1) {
    return `$${dollars.toFixed(3)}`;
  }
  return `$${dollars.toFixed(2)}`;
};
