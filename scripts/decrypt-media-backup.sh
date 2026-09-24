#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo 'Usage: scripts/decrypt-media-backup.sh <backup-file> <new-output-file>' >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"
bash mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/backup-decrypt-classpath.txt
java -cp "target/classes:$(cat target/backup-decrypt-classpath.txt)" \
  com.biliwind.blog.service.storage.BackupDecryptTool "$1" "$2"
