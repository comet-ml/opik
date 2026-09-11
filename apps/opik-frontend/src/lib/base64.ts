/**
 * Detects if a string is a valid base64 data URL for images or videos
 * @param str - The string to check
 * @returns true if the string is a base64 data URL, false otherwise
 */
export const isBase64DataUrl = (str: string): boolean => {
  if (!str || typeof str !== "string") {
    return false;
  }

  const trimmed = str.trim();

  // Check for data URL prefix for images or videos
  const imagePattern =
    /^data:image\/(png|jpeg|jpg|gif|webp|bmp|svg\+xml);base64,/i;
  const videoPattern = /^data:video\/(mp4|webm|ogg|avi|mov|wmv|flv);base64,/i;

  return imagePattern.test(trimmed) || videoPattern.test(trimmed);
};

/**
 * Calculates the size of a base64 string in megabytes
 * @param base64 - The base64 string (with or without data URL prefix)
 * @returns The size in MB
 */
export const getBase64SizeInMB = (base64: string): number => {
  if (!base64 || typeof base64 !== "string") {
    return 0;
  }

  // Remove the data URL prefix if present to get just the base64 data
  const base64Data = base64.includes(",") ? base64.split(",")[1] : base64;

  // Calculate the size
  // Base64 encoding increases size by ~33%, so we need to account for that
  // Actual size = (base64 length * 3) / 4
  const sizeInBytes = (base64Data.length * 3) / 4;

  // Handle padding characters ('=')
  const paddingCount = (base64Data.match(/=/g) || []).length;
  const actualSizeInBytes = sizeInBytes - paddingCount;

  // Convert to MB
  const sizeInMB = actualSizeInBytes / (1024 * 1024);

  return sizeInMB;
};
