#!/bin/sh

echo "$(pwd)"
echo "OPIK_VERSION=$OPIK_VERSION"

java -jar opik-backend-$OPIK_VERSION.jar db migrate config.yml \
  && java -jar opik-backend-$OPIK_VERSION.jar dbAnalytics migrate config.yml
