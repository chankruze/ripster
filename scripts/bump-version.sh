#!/usr/bin/env bash
# Bumps the app version in version.properties (read by Gradle for Android builds).
#
# Usage: scripts/bump-version.sh <major|minor|patch|build|set X.Y.Z> [--dry-run]
#   major | minor | patch   bump that part of VERSION_NAME (SemVer) and increment VERSION_CODE
#   build                   keep VERSION_NAME and only increment VERSION_CODE (e.g. a re-upload)
#   set X.Y.Z               set VERSION_NAME explicitly and increment VERSION_CODE
#   --dry-run               print the change without writing it
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="${VERSION_FILE:-$ROOT/version.properties}"
SEMVER='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

usage() {
  sed -n '4,8p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 1
}

fail() {
  echo "error: $*" >&2
  exit 1
}

read_prop() {
  grep -E "^$1=" "$VERSION_FILE" | head -n 1 | cut -d= -f2- | tr -d '[:space:]'
}

[ $# -ge 1 ] || usage
command="$1"
shift

explicit_name=""
if [ "$command" = "set" ]; then
  [ $# -ge 1 ] || usage
  explicit_name="$1"
  shift
fi

dry_run=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=true ;;
    *) usage ;;
  esac
done

[ -f "$VERSION_FILE" ] || fail "$VERSION_FILE not found"

name="$(read_prop VERSION_NAME)"
code="$(read_prop VERSION_CODE)"

[[ "$name" =~ $SEMVER ]] || fail "VERSION_NAME '$name' is not MAJOR.MINOR.PATCH"
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
[[ "$code" =~ ^[1-9][0-9]*$ ]] || fail "VERSION_CODE '$code' is not a positive integer"

case "$command" in
  major) new_name="$((major + 1)).0.0" ;;
  minor) new_name="$major.$((minor + 1)).0" ;;
  patch) new_name="$major.$minor.$((patch + 1))" ;;
  build) new_name="$name" ;;
  set)
    [[ "$explicit_name" =~ $SEMVER ]] || fail "'$explicit_name' is not MAJOR.MINOR.PATCH"
    new_name="$explicit_name"
    ;;
  *) usage ;;
esac
new_code=$((code + 1))

echo "VERSION_NAME: $name -> $new_name"
echo "VERSION_CODE: $code -> $new_code"

if [ "$dry_run" = true ]; then
  echo "(dry run: nothing written)"
  exit 0
fi

tmp="$(mktemp)"
sed -e "s/^VERSION_NAME=.*/VERSION_NAME=$new_name/" \
    -e "s/^VERSION_CODE=.*/VERSION_CODE=$new_code/" \
    "$VERSION_FILE" > "$tmp"
cat "$tmp" > "$VERSION_FILE" # keeps the original file's permissions
rm -f "$tmp"

echo "Updated ${VERSION_FILE#"$ROOT"/}"
if [ "$command" = "build" ]; then
  echo "Next: git add version.properties && git commit -m \"chore(release): v$new_name build $new_code\""
else
  echo "Next: git add version.properties && git commit -m \"chore(release): v$new_name\" && git tag v$new_name"
fi
