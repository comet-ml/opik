import { create } from "zustand";

interface AudioPlayerStore {
  currentlyPlayingId: string | null;
  setCurrentlyPlaying: (id: string | null) => void;
}

const useAudioPlayerStore = create<AudioPlayerStore>((set) => ({
  currentlyPlayingId: null,
  setCurrentlyPlaying: (id) => set({ currentlyPlayingId: id }),
}));

// Selector hooks for optimized re-renders
export const useCurrentlyPlayingId = () =>
  useAudioPlayerStore((state) => state.currentlyPlayingId);

export const useSetCurrentlyPlaying = () =>
  useAudioPlayerStore((state) => state.setCurrentlyPlaying);

export default useAudioPlayerStore;
