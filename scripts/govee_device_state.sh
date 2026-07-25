#!/usr/bin/env bash
set -euo pipefail

# Populate with your Govee API key (from the Govee Home app: Profile > Settings > Apply for API Key)
GOVEE_API_KEY="ab436707-1276-4de3-a6b6-2e1a31dac35c"

SKU="H5107"
DEVICE="01:32:52:C1:04:3A:C4:B4"

REQUEST_ID="$(uuidgen)"

curl --request POST \
  --url "https://openapi.api.govee.com/router/api/v1/device/state" \
  --header "Content-Type: application/json" \
  --header "Govee-API-Key: ${GOVEE_API_KEY}" \
  --data "{
    \"requestId\": \"${REQUEST_ID}\",
    \"payload\": {
        \"sku\": \"${SKU}\",
        \"device\": \"${DEVICE}\"
    }
}"
