#!/bin/sh
set -eu

umask 077

command="${1:-server}"
if [ "$#" -gt 0 ]; then
    shift
fi

case "$command" in
    server)
        exec /opt/someday/bin/server "$@"
        ;;
    bootstrap-admin)
        exec /opt/someday/bin/bootstrap-admin "$@"
        ;;
    verify-media-integrity)
        exec /opt/someday/bin/verify-media-integrity "$@"
        ;;
    *)
        echo "Unknown Someday container command: $command" >&2
        echo "Supported commands: server, bootstrap-admin, verify-media-integrity" >&2
        exit 64
        ;;
esac
