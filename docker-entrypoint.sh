#!/bin/bash
#
# Octi Server Entrypoint Script
#
# Environment variables:
# - OCTI_PORT: Server port (default: 8080)
# - OCTI_DEBUG: Enable debug mode (default: false)
# - OCTI_DATA_DIR: Override data directory path (default: auto-detect)
#
# Default data path: /etc/octi-server
# Deprecated path: /etc/octi-sync-server (still supported via auto-detection)
#

set -e

# Process environment variables
OCTI_PORT=${OCTI_PORT:-8080}
OCTI_DEBUG=${OCTI_DEBUG:-false}

# Validate port
if ! [[ "$OCTI_PORT" =~ ^[0-9]+$ ]] || [ "$OCTI_PORT" -lt 1 ] || [ "$OCTI_PORT" -gt 65535 ]; then
    echo "Error: Invalid port number. Must be between 1-65535"
    exit 1
fi

# Resolve data directory
OLD_DATA_DIR="/etc/octi-sync-server"
NEW_DATA_DIR="/etc/octi-server"

if [ -n "$OCTI_DATA_DIR" ]; then
    DATA_DIR="$OCTI_DATA_DIR"
    echo "Using data directory from OCTI_DATA_DIR: $DATA_DIR"
elif mountpoint -q "$OLD_DATA_DIR" 2>/dev/null && mountpoint -q "$NEW_DATA_DIR" 2>/dev/null; then
    echo "ERROR: Both $OLD_DATA_DIR and $NEW_DATA_DIR are mounted. Set OCTI_DATA_DIR to choose." >&2
    exit 1
elif mountpoint -q "$OLD_DATA_DIR" 2>/dev/null; then
    echo "WARNING: $OLD_DATA_DIR is deprecated, please migrate your volume mount to $NEW_DATA_DIR" >&2
    DATA_DIR="$OLD_DATA_DIR"
else
    DATA_DIR="$NEW_DATA_DIR"
fi

# Build command arguments
CMD_ARGS="--datapath=$DATA_DIR --port=$OCTI_PORT"

# Add debug flag if enabled
if [ "$OCTI_DEBUG" = "true" ]; then
    CMD_ARGS="$CMD_ARGS --debug"
fi

# Execute the application
exec ./bin/octi-server $CMD_ARGS
