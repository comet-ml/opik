import { useSyncExternalStore } from "react";

const subscribe = (listener: () => void) =>
  window.opikBridge?.subscribeCapabilities?.(listener) ?? (() => {});

const getSnapshot = () => Boolean(window.opikBridge?.canProposeArtifacts?.());

const useCanProposeArtifacts = () =>
  useSyncExternalStore(subscribe, getSnapshot);

export default useCanProposeArtifacts;
