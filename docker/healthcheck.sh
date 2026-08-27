#!/usr/bin/env bash
set -Eeuo pipefail

port="${SOMEDAY_PORT:-3180}"
exec 3<>"/dev/tcp/127.0.0.1/${port}"
printf 'GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3

IFS=$'\r' read -r status_line <&3
[[ "$status_line" == "HTTP/1.1 200 "* || "$status_line" == "HTTP/1.0 200 "* ]]
