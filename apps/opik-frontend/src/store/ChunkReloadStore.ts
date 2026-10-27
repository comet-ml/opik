import { create } from "zustand";

interface ChunkReloadStore {
  reloadPending: boolean;
}

const useChunkReloadStore = create<ChunkReloadStore>(() => ({
  reloadPending: false,
}));

export const useIsChunkReloadPending = () =>
  useChunkReloadStore((state) => state.reloadPending);

export default useChunkReloadStore;
