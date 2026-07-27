#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Compatibility wrapper around the unified mobile version bumper.
args=(--platform ios)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-number)
      args+=(--ios-build-number "${2:-}")
      shift 2
      ;;
    --version|--marketing-version)
      args+=(--ios-marketing-version "${2:-}")
      shift 2
      ;;
    --no-commit|--dry-run|--help|-h)
      args+=("$1")
      shift 1
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: ./scripts/bump-ios-version.sh [--build-number N] [--version X] [--no-commit] [--dry-run]" >&2
      exit 2
      ;;
  esac
done

exec "$ROOT_DIR/scripts/bump-mobile-version.sh" "${args[@]}"
