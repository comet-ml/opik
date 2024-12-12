#!/bin/bash

max_retries=12
wait_interval=5
retries=0

url="http://localhost:5173/api/health-check?name=all&type=ready"

while (( retries < max_retries ))
do
  response=$(curl -s -o /dev/null -w "%{http_code}" $url)
  if [[ $response -eq 200 ]]; then
    echo "Backend is up and healthy!"
    exit 0
  else
    echo "Waiting for backend to be ready... (Attempt: $((retries+1))/$max_retries, Status Code: $response)"
    sleep $wait_interval
    retries=$((retries+1))
  fi
done

echo "Error: Backend did not respond with 200 OK after $((max_retries * wait_interval)) seconds."
exit 1
