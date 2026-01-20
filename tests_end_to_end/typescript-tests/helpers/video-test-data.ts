/**
 * Video Test Data Helper
 * Provides test video URLs and dataset configurations for video support tests
 */

export interface VideoTestData {
  name: string;
  url: string;
  description: string;
  expectedKeywords: string[];
  duration?: number;
}

/**
 * Test videos from Pixabay (royalty-free)
 */
export const TEST_VIDEOS: Record<string, VideoTestData> = {
  sunset: {
    name: 'Dubai Sunset Time-lapse',
    url: 'https://cdn.pixabay.com/video/2022/03/18/111204-689949818_small.mp4',
    description: 'Time-lapse of city skyline during sunset',
    expectedKeywords: ['sunset', 'sky', 'city', 'skyline', 'time-lapse', 'buildings'],
    duration: 16,
  },
  ocean: {
    name: 'Portugal Coastal Waves',
    url: 'https://cdn.pixabay.com/video/2023/04/28/160767-822213540_small.mp4',
    description: 'Aerial view of ocean waves and coastal architecture',
    expectedKeywords: ['ocean', 'waves', 'beach', 'coastal', 'water', 'aerial'],
    duration: 10,
  },
};

/**
 * Get a random test video
 */
export function getRandomTestVideo(): VideoTestData {
  const videos = Object.values(TEST_VIDEOS);
  return videos[Math.floor(Math.random() * videos.length)];
}

/**
 * Get all test video URLs
 */
export function getAllTestVideoUrls(): string[] {
  return Object.values(TEST_VIDEOS).map(v => v.url);
}

/**
 * Create dataset items with video URLs
 */
export function createVideoDatasetItems(count: number = 2): Array<Record<string, any>> {
  const items: Array<Record<string, any>> = [];
  const videos = Object.values(TEST_VIDEOS);
  
  for (let i = 0; i < count; i++) {
    const video = videos[i % videos.length];
    items.push({
      input: `What is shown in this video? (Item ${i + 1})`,
      video_url: video.url,
      expected_description: video.description,
    });
  }
  
  return items;
}

/**
 * Verify response contains expected video keywords
 */
export function verifyVideoAnalysisKeywords(response: string, video: VideoTestData): boolean {
  const lowerResponse = response.toLowerCase();
  const matchCount = video.expectedKeywords.filter(keyword => 
    lowerResponse.includes(keyword.toLowerCase())
  ).length;
  
  // At least 2 keywords should match for valid video analysis
  return matchCount >= 2;
}






