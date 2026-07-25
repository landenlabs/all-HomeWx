#!/usr/bin/env bash
set -euo pipefail

# Populate with your Govee API key (from the Govee Home app: Profile > Settings > Apply for API Key)
GOVEE_API_KEY="ab436707-1276-4de3-a6b6-2e1a31dac35c"

curl --request GET \
  --url "https://openapi.api.govee.com/router/api/v1/user/devices" \
  --header "Content-Type: application/json" \
  --header "Govee-API-Key: ${GOVEE_API_KEY}"
