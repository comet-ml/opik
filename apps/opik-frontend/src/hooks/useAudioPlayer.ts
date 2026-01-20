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
  hasError: boolean;
  errorMessage: string | null;
  audioRef: RefObject<AudioPlayer>;
  handleLoadedMetaData: (e: Event) => void;
  handleListen: (e: Event) => void;
  handlePlay: () => void;
  handlePause: () => void;
  handleCanPlay: () => void;
  handleWaiting: () => void;
  handleLoadStart: () => void;
  handleError: (e: Event) => void;
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
  const [hasError, setHasError] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
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
    setHasError(false);
    setErrorMessage(null);
  }, []);

  const handleError = useCallback((e: Event) => {
    const audio = e.target as HTMLAudioElement;
    const error = audio.error;

    setIsLoading(false);
    setHasError(true);

    if (error) {
      switch (error.code) {
        case 1: // MEDIA_ERR_ABORTED
          setErrorMessage("Audio loading was cancelled");
          break;
        case 2: // MEDIA_ERR_NETWORK
          setErrorMessage("Network error loading audio");
          break;
        case 3: // MEDIA_ERR_DECODE
          setErrorMessage("Audio format error");
          break;
        case 4: // MEDIA_ERR_SRC_NOT_SUPPORTED
          setErrorMessage("Audio file not available or format not supported");
          break;
        default:
          setErrorMessage("Unable to load audio file");
      }
    } else {
      setErrorMessage("Audio playback error");
    }
  }, []);

  return {
    duration,
    currentTime,
    isPlaying,
    isLoading,
    hasError,
    errorMessage,
    audioRef,
    handleLoadedMetaData,
    handleListen,
    handlePlay,
    handlePause,
    handleCanPlay,
    handleWaiting,
    handleLoadStart,
    handleError,
    formatTime,
  };
};
