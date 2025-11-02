#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/.." && pwd -P)"
cd "${repo_root}"

if ! command -v git >/dev/null 2>&1; then
  echo "git muss installiert sein, um das Archiv zu erstellen." >&2
  exit 1
fi

mkdir -p dist

archive_name="AndroidFotobox-$(date +%Y%m%d-%H%M%S).zip"
archive_path="dist/${archive_name}"

echo "Erstelle ${archive_path}..."

git archive --format=zip HEAD -o "${archive_path}"

echo "Fertig! Du findest das Archiv unter: ${archive_path}"
