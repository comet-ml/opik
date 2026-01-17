import {
  useState,
  useRef,
  useCallback,
  RefObject,
  useEffect,
  useId,
} from "react";
import AudioPlayer from "react-h5-audio-player";
import {
  useCurrentlyPlayingId,
  useSetCurrentlyPlaying,
} from "@/store/AudioPlayerStore";

interface UseAudioPlayerOptions {
  url: string;
  autoPlay?: boolean;
}

interface UseAudioPlayerReturn {
  duration: number;
  currentTime: number;
  isPlaying: boolean;
  isLoading: boolean;
  audioRef: RefObject<AudioPlayer>;
  handleLoadedMetaData: (e: Event) => void;
  handleListen: (e: Event) => void;
  handlePlay: () => void;
  handlePause: () => void;
  handleCanPlay: () => void;
  handleWaiting: () => void;
  handleLoadStart: () => void;
  formatTime: (seconds: number) => string;
}

export const useAudioPlayer = (
  options: UseAudioPlayerOptions,
): UseAudioPlayerReturn => {
  const { autoPlay = false } = options;
  const playerId = useId();
  const currentlyPlayingId = useCurrentlyPlayingId();
  const setCurrentlyPlaying = useSetCurrentlyPlaying();

  const [duration, setDuration] = useState<number>(0);
  const [currentTime, setCurrentTime] = useState<number>(0);
  const [isPlaying, setIsPlaying] = useState<boolean>(autoPlay);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const audioRef = useRef<AudioPlayer>(null);

  // Pause this audio if another audio starts playing
  useEffect(() => {
    if (
      currentlyPlayingId !== null &&
      currentlyPlayingId !== playerId &&
      isPlaying
    ) {
      audioRef.current?.audio.current?.pause();
    }
  }, [currentlyPlayingId, playerId, isPlaying]);

  const formatTime = useCallback((seconds: number): string => {
    if (isNaN(seconds) || !isFinite(seconds)) return "0:00";

    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  }, []);

  const handleLoadedMetaData = useCallback((e: Event) => {
    const audio = e.target as HTMLAudioElement;
    if (audio.duration && isFinite(audio.duration)) {
      setDuration(audio.duration);
      setIsLoading(false);
    }
  }, []);

  const handleListen = useCallback((e: Event) => {
    const audio = e.target as HTMLAudioElement;
    setCurrentTime(audio.currentTime);
  }, []);

  const handlePlay = useCallback(() => {
    setIsPlaying(true);
    setCurrentlyPlaying(playerId);
  }, [playerId, setCurrentlyPlaying]);

  const handlePause = useCallback(() => {
    setIsPlaying(false);
    setCurrentlyPlaying(null);
  }, [setCurrentlyPlaying]);

  const handleCanPlay = useCallback(() => {
    setIsLoading(false);
  }, []);

  const handleWaiting = useCallback(() => {
    setIsLoading(true);
  }, []);

  const handleLoadStart = useCallback(() => {
    setIsLoading(true);
  }, []);

  return {
    duration,
    currentTime,
    isPlaying,
    isLoading,
    audioRef,
    handleLoadedMetaData,
    handleListen,
    handlePlay,
    handlePause,
    handleCanPlay,
    handleWaiting,
    handleLoadStart,
    formatTime,
  };
};
