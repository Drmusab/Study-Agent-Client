#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Starting Mock PC Study Agent..."
python3 "$SCRIPT_DIR/mock_pc_agent.py"
