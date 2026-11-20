#!/usr/bin/env bash
# Muse-side controller for Fake Call (Tailscale receive-server, app v1.0.0).
#
#   send_call.sh --ping
#   send_call.sh --status
#   send_call.sh [--dialog] <audio-file> [message] [morning true/false]
#   send_call.sh --call-text [--morning] <text> [message]
#   send_call.sh --say <audio-file>
#   send_call.sh --say-text [--no-split] <text>
#   send_call.sh --utterance
#   send_call.sh --wait-utterance <last-id> [timeout-ms]
#   send_call.sh --events [since-seq] [timeout-ms]
#   send_call.sh --accept [greeting-text]
#   send_call.sh --hangup [reason]
#
# --say-text       : POST /say_text — phone synthesizes directly via PC Irodori-TTS
#                    over local Tailscale LAN (eliminates ~2-3 s/sentence proxy overhead)
# --events         : GET  /events?since=<seq>&timeout=<ms> — Long Polling (<50 ms latency)
# --wait-utterance : GET  /utterance?since=<id>&timeout=<ms> — Long Polling drop-in
# --hangup         : POST /hangup — official call termination (replaces screen-tap hack)
#
# All legacy flags (--ping, --dialog, --say, --utterance) stay 100% compatible.
set -euo pipefail

HOST="${FAKECALL_HOST:-100.64.0.2}"   # <PHONE_TAILSCALE_IP>
PORT="${FAKECALL_PORT:-8791}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOKEN_FILE="${FAKECALL_TOKEN_FILE:-$SCRIPT_DIR/token.txt}"

CURL_PROXY_ARGS=()
if [ -n "${HTTPS_PROXY:-}" ]; then
  PROXY="${HTTPS_PROXY%:*}:3130"
  CURL_PROXY_ARGS=(--proxytunnel --proxy "$PROXY")
fi

if [ ! -f "$TOKEN_FILE" ]; then
  echo "error: token file not found: $TOKEN_FILE" >&2
  echo "Copy the token shown in the Fake Call app into token.txt next to send_call.sh." >&2
  exit 1
fi
TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"
if [ -z "$TOKEN" ]; then
  echo "error: token file is empty: $TOKEN_FILE" >&2
  exit 1
fi

usage() {
  cat >&2 <<EOF
usage: $0 --ping
       $0 --status
       $0 [--dialog] <audio-file> [message] [morning true/false]
       $0 --call-text [--morning] <text> [message]
       $0 --say <audio-file>
       $0 --say-text [--no-split] <text>
       $0 --utterance
       $0 --wait-utterance <last-id> [timeout-ms]
       $0 --events [since-seq] [timeout-ms]
       $0 --accept [greeting-text]
       $0 --hangup [reason]
EOF
}

audio_ext() {
  local f="$1" ext
  ext="${f##*.}"
  if [ "$ext" = "$f" ]; then ext="wav"; fi
  printf '%s' "$ext" | tr '[:upper:]' '[:lower:]'
}

check_audio_file() {
  if [ -z "$1" ] || [ ! -f "$1" ]; then
    echo "error: audio file not found: $1" >&2
    exit 1
  fi
}

url_encode() {
  python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1"
}

case "${1:-}" in
  --ping)
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 15 \
      -H "X-Token: $TOKEN" "http://$HOST:$PORT/ping"
    echo
    exit 0
    ;;
  --status)
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 15 \
      -H "X-Token: $TOKEN" "http://$HOST:$PORT/status"
    echo
    exit 0
    ;;
  --utterance)
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 15 \
      -H "X-Token: $TOKEN" "http://$HOST:$PORT/utterance"
    echo
    exit 0
    ;;
  --wait-utterance)
    SINCE="${2:-0}"
    TIMEOUT_MS="${3:-25000}"
    MAX_SEC=$(( (TIMEOUT_MS / 1000) + 12 ))
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time "$MAX_SEC" \
      -H "X-Token: $TOKEN" \
      "http://$HOST:$PORT/utterance?since=$SINCE&timeout=$TIMEOUT_MS"
    echo
    exit 0
    ;;
  --events)
    SINCE="${2:-}"
    TIMEOUT_MS="${3:-25000}"
    MAX_SEC=$(( (TIMEOUT_MS / 1000) + 12 ))
    QS="timeout=$TIMEOUT_MS"
    if [ -n "$SINCE" ]; then
      QS="since=$SINCE&$QS"
    fi
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time "$MAX_SEC" \
      -H "X-Token: $TOKEN" \
      "http://$HOST:$PORT/events?$QS"
    echo
    exit 0
    ;;
  --say)
    AUDIO="${2:-}"
    check_audio_file "$AUDIO"
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 30 \
      -X POST --data-binary "@$AUDIO" \
      -H "X-Token: $TOKEN" \
      -H "Content-Type: application/octet-stream" \
      -H "X-Audio-Ext: $(audio_ext "$AUDIO")" \
      "http://$HOST:$PORT/say"
    echo
    exit 0
    ;;
  --say-text)
    shift
    SPLIT="true"
    if [ "${1:-}" = "--no-split" ]; then
      SPLIT="false"
      shift
    fi
    TEXT="${1:-}"
    if [ -z "$TEXT" ]; then
      usage
      exit 1
    fi
    JSON_BODY="$(python3 -c 'import sys, json; print(json.dumps({"text": sys.argv[1], "split": sys.argv[2] == "true"}, ensure_ascii=False))' "$TEXT" "$SPLIT")"
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 35 \
      -X POST --data-binary "$JSON_BODY" \
      -H "X-Token: $TOKEN" \
      -H "Content-Type: application/json; charset=utf-8" \
      "http://$HOST:$PORT/say_text"
    echo
    exit 0
    ;;
  --call-text)
    shift
    MORNING="false"
    if [ "${1:-}" = "--morning" ]; then
      MORNING="true"
      shift
    fi
    TEXT="${1:-}"
    if [ -z "$TEXT" ]; then
      usage
      exit 1
    fi
    MESSAGE="${2:-$TEXT}"
    ENC_TEXT="$(url_encode "$TEXT")"
    ENC_MSG="$(url_encode "$MESSAGE")"
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 35 \
      -X POST --data-binary "" \
      -H "X-Token: $TOKEN" \
      -H "X-Caller: Muse" \
      -H "X-Dialog: 1" \
      -H "X-Morning: $MORNING" \
      -H "X-Text: $ENC_TEXT" \
      -H "X-Message: $ENC_MSG" \
      "http://$HOST:$PORT/call"
    echo
    exit 0
    ;;
  --accept)
    TEXT="${2:-}"
    ACCEPT_ARGS=(
      "${CURL_PROXY_ARGS[@]}" -sS --max-time 35
      -X POST --data-binary ""
      -H "X-Token: $TOKEN"
    )
    if [ -n "$TEXT" ]; then
      ENC_TEXT="$(url_encode "$TEXT")"
      ACCEPT_ARGS+=(-H "X-Text: $ENC_TEXT")
    fi
    curl "${ACCEPT_ARGS[@]}" "http://$HOST:$PORT/accept"
    echo
    exit 0
    ;;
  --hangup)
    REASON="${2:-remote_hangup}"
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 15 \
      -X POST --data-binary "" \
      -H "X-Token: $TOKEN" \
      -H "X-Reason: $REASON" \
      "http://$HOST:$PORT/hangup"
    echo
    exit 0
    ;;
  --schedule)
    HHMM="${2:-}"
    MODE="${3:-daily}"
    if [ -z "$HHMM" ]; then
      echo "usage: $0 --schedule HH:MM [daily|once]" >&2
      exit 1
    fi
    HOUR="${HHMM%%:*}"
    MINUTE="${HHMM##*:}"
    DAILY="true"
    if [ "$MODE" = "once" ]; then DAILY="false"; fi
    JSON_BODY="$(python3 -c 'import sys, json; print(json.dumps({"enabled": True, "hour": int(sys.argv[1]), "minute": int(sys.argv[2]), "daily": sys.argv[3] == "true"}))' "$HOUR" "$MINUTE" "$DAILY")"
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 15 \
      -X POST --data-binary "$JSON_BODY" \
      -H "X-Token: $TOKEN" \
      -H "Content-Type: application/json; charset=utf-8" \
      "http://$HOST:$PORT/schedule"
    echo
    exit 0
    ;;
  --schedule-off)
    curl "${CURL_PROXY_ARGS[@]}" -sS --max-time 15 \
      -X POST --data-binary '{"enabled":false}' \
      -H "X-Token: $TOKEN" \
      -H "Content-Type: application/json; charset=utf-8" \
      "http://$HOST:$PORT/schedule"
    echo
    exit 0
    ;;
esac

DIALOG=0
if [ "${1:-}" = "--dialog" ]; then
  DIALOG=1
  shift
fi

AUDIO="${1:-}"
if [ -z "$AUDIO" ]; then
  usage
  exit 1
fi
check_audio_file "$AUDIO"
MESSAGE="${2:-}"
MORNING="${3:-false}"

ARGS=(
  "${CURL_PROXY_ARGS[@]}" -sS --max-time 30
  -X POST --data-binary "@$AUDIO"
  -H "X-Token: $TOKEN"
  -H "Content-Type: application/octet-stream"
  -H "X-Audio-Ext: $(audio_ext "$AUDIO")"
  -H "X-Morning: $MORNING"
  -H "X-Caller: Muse"
)
if [ "$DIALOG" = "1" ]; then
  ARGS+=(-H "X-Dialog: 1")
fi
if [ -n "$MESSAGE" ]; then
  ENCODED="$(url_encode "$MESSAGE")"
  ARGS+=(-H "X-Message: $ENCODED")
fi

curl "${ARGS[@]}" "http://$HOST:$PORT/call"
echo
