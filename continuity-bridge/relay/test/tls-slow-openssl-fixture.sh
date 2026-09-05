#!/bin/sh
set -eu
if [ "$#" -eq 0 ]; then sleep 2; exit 0; fi
if [ "${1:-}" = "genpkey" ]; then sleep 2; fi
exec /opt/homebrew/bin/openssl "$@"
