#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  echo "Usage: $0 <image-tag>" >&2
  exit 64
fi

# Registry connections can fail after layers upload but before a tag is stored.
# Retrying the same tag reuses uploaded layers and still fails after five attempts.
for attempt in 1 2 3 4 5; do
  if docker push "$1"; then
    exit 0
  else
    push_status=$?
  fi

  if (( attempt == 5 )); then
    exit "$push_status"
  fi

  retry_delay=$((attempt * 15))
  echo "Image push failed (attempt ${attempt}/5); retrying in ${retry_delay}s: $1" >&2
  sleep "$retry_delay"
done
