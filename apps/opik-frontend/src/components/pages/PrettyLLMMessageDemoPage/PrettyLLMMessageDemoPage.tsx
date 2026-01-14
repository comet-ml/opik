import React, { useState } from "react";
import PrettyLLMMessage from "@/components/shared/PrettyLLMMessage";

const PrettyLLMMessageDemoPage: React.FC = () => {
  const [expandedValue, setExpandedValue] = useState<string[]>([
    "assistant",
    "multipleAudios",
  ]);

  return (
    <div className="container mx-auto max-w-4xl space-y-4 p-8">
      <h1 className="mb-8 text-3xl font-bold">
        PrettyLLMMessage Component Demo
      </h1>

      <PrettyLLMMessage.Container
        value={expandedValue}
        onValueChange={setExpandedValue}
        type="multiple"
      >
        {/* System Message */}
        <PrettyLLMMessage.Root value="system">
          <PrettyLLMMessage.Header role="system" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.TextBlock>
              Plan customized tours based on user preferences, providing concise
              and clear travel guidance. You are a travel-planning assistant
              that helps users discover amazing destinations and create
              memorable experiences. Your responses should be informative,
              friendly, and tailored to each user's specific needs and
              interests.
            </PrettyLLMMessage.TextBlock>
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* User Message */}
        <PrettyLLMMessage.Root value="user">
          <PrettyLLMMessage.Header role="user" label="john@example.com" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.TextBlock>
              What are the best places to visit in Madrid? I'm interested in
              historical sites and local cuisine.
            </PrettyLLMMessage.TextBlock>
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* Assistant Message with Multiple Content Types */}
        <PrettyLLMMessage.Root value="assistant">
          <PrettyLLMMessage.Header role="assistant" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.TextBlock>
              Madrid is a fantastic city with rich history and amazing food!
              Here are some top recommendations for historical sites and local
              cuisine. The Royal Palace is a must-see, and you should definitely
              try the tapas at Mercado de San Miguel.
            </PrettyLLMMessage.TextBlock>
            <PrettyLLMMessage.ImageBlock
              images={[
                {
                  url: "https://images.unsplash.com/photo-1539037116277-4db20889f2d4?w=400",
                  name: "plaza_mayor.png",
                },
                {
                  url: "https://images.unsplash.com/photo-1571068316344-75bc76f77890?w=400",
                  name: "royal_palace.png",
                },
              ]}
            />
            <PrettyLLMMessage.AudioPlayerBlock
              url="https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
              name="voice_note_01.mp3"
            />
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* Tool Call with JSON Input */}
        <PrettyLLMMessage.Root value="tool">
          <PrettyLLMMessage.Header role="tool" label="route_planner" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.CodeBlock
              label="Tool input"
              code={JSON.stringify(
                {
                  max_duration_minutes: 120,
                  start_point: "Plaza Mayor",
                  avoid_hills: true,
                  preferences: {
                    walking_speed: "moderate",
                    include_restaurants: true,
                  },
                },
                null,
                2,
              )}
            />
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* Tool Call with Code Block */}
        <PrettyLLMMessage.Root value="toolCode">
          <PrettyLLMMessage.Header role="tool" label="tool_input" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.CodeBlock
              label="JSON"
              code={`{
  "max_duration_minutes": 120,
  "start_point": "Plaza Mayor",
  "avoid_hills": true,
  "preferences": {
    "walking_speed": "moderate",
    "include_restaurants": true
  }
}`}
            />
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* Assistant Message with Video */}
        <PrettyLLMMessage.Root value="assistantVideo">
          <PrettyLLMMessage.Header role="assistant" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.TextBlock>
              Here's a video guide to help you navigate Madrid's historic
              center.
            </PrettyLLMMessage.TextBlock>
            <PrettyLLMMessage.VideoBlock
              videos={[
                {
                  url: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                  name: "madrid_guide.mp4",
                },
              ]}
            />
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* Long Text Message (to test truncation) */}
        <PrettyLLMMessage.Root value="longText">
          <PrettyLLMMessage.Header role="assistant" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.TextBlock>
              This is a very long message that should be truncated to 3 lines
              with a "Show more" button appearing below it. The text continues
              here to demonstrate the truncation functionality. Lorem ipsum
              dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor
              incididunt ut labore et dolore magna aliqua. Ut enim ad minim
              veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip
              ex ea commodo consequat. Duis aute irure dolor in reprehenderit in
              voluptate velit esse cillum dolore eu fugiat nulla pariatur.
              Excepteur sint occaecat cupidatat non proident, sunt in culpa qui
              officia deserunt mollit anim id est laborum. Sed ut perspiciatis
              unde omnis iste natus error sit voluptatem accusantium doloremque
              laudantium.
            </PrettyLLMMessage.TextBlock>
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>

        {/* Multiple Audio Players (Array format) */}
        <PrettyLLMMessage.Root value="multipleAudios">
          <PrettyLLMMessage.Header role="assistant" />
          <PrettyLLMMessage.Content>
            <PrettyLLMMessage.TextBlock>
              Here are some audio recordings from our Madrid trip. Each audio
              has its own player with play/pause and seek controls.
            </PrettyLLMMessage.TextBlock>
            <PrettyLLMMessage.AudioPlayerBlock
              audios={[
                {
                  url: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                  name: "madrid_street_sounds.mp3",
                },
                {
                  url: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                  name: "flamenco_performance.mp3",
                },
                {
                  url: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
                  name: "local_market_ambience.mp3",
                },
                {
                  url: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
                  name: "tapas_bar_conversation.mp3",
                },
              ]}
            />
          </PrettyLLMMessage.Content>
        </PrettyLLMMessage.Root>
      </PrettyLLMMessage.Container>
    </div>
  );
};

export default PrettyLLMMessageDemoPage;
