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
  formatTime: (seconds: number) => string;
}

export const useAudioPlayer = (
  options: UseAudioPlayerOptions,
): UseAudioPlayerReturn => {
  const { url, autoPlay = false } = options;
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

  // Attach native event listeners to the audio element
  useEffect(() => {
    const audio = audioRef.current?.audio.current;
    if (!audio) return;

    // Duration change handler with "huge seek" workaround for Infinity
    const onDurationChange = () => {
      // Handle Infinity duration (streaming/chunked audio without proper headers)
      if (audio.duration === Infinity) {
        // Force browser to find actual duration by seeking to end
        const originalTime = audio.currentTime;
        audio.currentTime = Number.MAX_SAFE_INTEGER;

        // Wait for timeupdate to reset position
        const resetPosition = () => {
          audio.removeEventListener("timeupdate", resetPosition);
          audio.currentTime = originalTime;
          // Duration should now be correct
          if (audio.duration && isFinite(audio.duration)) {
            setDuration(audio.duration);
          }
        };
        audio.addEventListener("timeupdate", resetPosition, { once: true });
        return;
      }

      if (audio.duration && isFinite(audio.duration)) {
        setDuration(audio.duration);
      }
    };

    // Loaded metadata handler - early duration capture
    const onLoadedMetadata = () => {
      if (audio.duration && isFinite(audio.duration)) {
        setDuration(audio.duration);
      }
    };

    // Time update handler - native browser event (~4 times/second)
    const onTimeUpdate = () => {
      setCurrentTime(audio.currentTime);
    };

    // Play handler
    const onPlay = () => {
      setIsPlaying(true);
      setCurrentlyPlaying(playerId);
    };

    // Pause handler
    const onPause = () => {
      setIsPlaying(false);
      // Only clear global state if this player owns it
      if (currentlyPlayingId === playerId) {
        setCurrentlyPlaying(null);
      }
    };

    // Can play handler - audio is ready to play
    const onCanPlay = () => {
      setIsLoading(false);
    };

    // Waiting handler - audio is buffering
    const onWaiting = () => {
      setIsLoading(true);
    };

    // Load start handler - audio loading begins
    const onLoadStart = () => {
      setIsLoading(true);
      setHasError(false);
      setErrorMessage(null);
    };

    // Ended handler - final fallback for duration capture
    const onEnded = () => {
      // At this point, duration should be definitively known
      if (audio.duration && isFinite(audio.duration)) {
        setDuration(audio.duration);
      }
    };

    // Error handler
    const onError = () => {
      const error = audio.error;

      console.log("[DEBUG] useAudioPlayer: onError", error);

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
    };

    // Attach all event listeners
    audio.addEventListener("loadedmetadata", onLoadedMetadata);
    audio.addEventListener("durationchange", onDurationChange);
    audio.addEventListener("timeupdate", onTimeUpdate);
    audio.addEventListener("play", onPlay);
    audio.addEventListener("pause", onPause);
    audio.addEventListener("ended", onEnded);
    audio.addEventListener("canplay", onCanPlay);
    audio.addEventListener("waiting", onWaiting);
    audio.addEventListener("loadstart", onLoadStart);
    audio.addEventListener("error", onError);

    // Cleanup function
    return () => {
      audio.removeEventListener("loadedmetadata", onLoadedMetadata);
      audio.removeEventListener("durationchange", onDurationChange);
      audio.removeEventListener("timeupdate", onTimeUpdate);
      audio.removeEventListener("play", onPlay);
      audio.removeEventListener("pause", onPause);
      audio.removeEventListener("ended", onEnded);
      audio.removeEventListener("canplay", onCanPlay);
      audio.removeEventListener("waiting", onWaiting);
      audio.removeEventListener("loadstart", onLoadStart);
      audio.removeEventListener("error", onError);
    };
  }, [url, playerId, setCurrentlyPlaying, currentlyPlayingId]);

  const formatTime = useCallback((seconds: number): string => {
    if (isNaN(seconds) || !isFinite(seconds)) return "0:00";

    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  }, []);

  return {
    duration,
    currentTime,
    isPlaying,
    isLoading,
    hasError,
    errorMessage,
    audioRef,
    formatTime,
  };
};
