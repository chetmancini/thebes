#!/bin/sh
# Thebes has no package manifest; release versions live in annotated Git tags.
set -eu

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

valid_version() {
    printf '%s\n' "$1" | LC_ALL=C grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
}

latest=$(git tag --list 'v*' --sort=-version:refname |
    LC_ALL=C awk '/^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/ && !found {sub(/^v/, ""); print; found=1}')
current=${latest:-0.0.0}

case "${1:-}" in
    version) printf '%s\n' "$current"; exit 0 ;;
    release) ;;
    *) fail 'Usage: release.sh version | release [patch|minor|major] [VERSION]' ;;
esac

bump=${2:-patch}
case "$bump" in
    patch|minor|major) ;;
    *) fail 'BUMP must be patch, minor, or major.' ;;
esac

version=${3:-}
if [ -z "$version" ]; then
    version=$(printf '%s\n' "$current" | awk -F. -v bump="$bump" '{
        if (bump == "major") printf "%d.0.0\n", $1 + 1;
        else if (bump == "minor") printf "%d.%d.0\n", $1, $2 + 1;
        else printf "%d.%d.%d\n", $1, $2, $3 + 1;
    }')
fi
valid_version "$version" || fail 'VERSION must be MAJOR.MINOR.PATCH, without a v prefix or leading zeros.'
printf '%s\n%s\n' "$current" "$version" | awk -F. '
    NR == 1 {major=$1; minor=$2; patch=$3; next}
    {exit !($1 > major || ($1 == major && $2 > minor) || ($1 == major && $2 == minor && $3 > patch))}
' || fail "VERSION must be greater than $current."

[ -z "$(git status --porcelain)" ] || fail 'Commit or stash changes before cutting a release.'
head=$(git rev-parse HEAD)
git show-ref --verify --quiet "refs/tags/v$version" && fail "Tag v$version already exists."

printf 'Checking release v%s...\n' "$version"
make check
[ "$(git rev-parse HEAD)" = "$head" ] || fail 'HEAD changed during checks; release aborted.'
[ -z "$(git status --porcelain)" ] || fail 'The working tree changed during checks; release aborted.'
git tag -a "v$version" "$head" -m "Release v$version"
printf 'Created local tag v%s. To publish it: git push origin v%s\n' "$version" "$version"
