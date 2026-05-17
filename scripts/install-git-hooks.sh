#!/bin/sh
# Symlink the tracked hooks in scripts/git-hooks/ into .git/hooks/.
# Idempotent: re-running just refreshes the symlinks.

set -e

repo_root=$(git rev-parse --show-toplevel)
src="$repo_root/scripts/git-hooks"
dst="$repo_root/.git/hooks"

mkdir -p "$dst"
for hook in "$src"/*; do
    name=$(basename "$hook")
    chmod +x "$hook"
    ln -sfn "../../scripts/git-hooks/$name" "$dst/$name"
    echo "installed: $dst/$name -> scripts/git-hooks/$name"
done
