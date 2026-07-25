#!/usr/bin/env bash
set -euo pipefail

# Populate with your Govee API key (from the Govee Home app: Profile > Settings > Apply for API Key)
# GOVEE_API_KEY="..."

curl --request GET \
  --url "https://openapi.api.govee.com/router/api/v1/user/devices" \
  --header "Content-Type: application/json" \
  --header "Govee-API-Key: ${GOVEE_API_KEY}"
