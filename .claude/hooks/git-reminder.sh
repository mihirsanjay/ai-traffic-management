#!/usr/bin/env bash
# Stop hook: suggest the next git action when one is actually due.
#
# Emits at most ONE suggestion, or stays silent. Silence is the common case and
# the point -- a hook that speaks every turn gets ignored, which defeats it.
#
# No jq dependency: it is not installed on this machine, and every git fact this
# needs comes from git itself rather than the hook's stdin payload.
#
# Contract: print a JSON object with systemMessage (shown to the user) and
# hookSpecificOutput.additionalContext (injected into Claude's context so it can
# raise the suggestion conversationally). Always exit 0 -- a Stop hook that
# fails non-zero would interfere with the turn ending.

set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_DIR" 2>/dev/null || exit 0

# --- silent unless this is a git repo -----------------------------------------
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# --- cooldown: at most one nudge per 15 minutes -------------------------------
COOLDOWN_SECONDS=900
STATE_DIR="${TMPDIR:-/tmp}/claude-git-reminder"
mkdir -p "$STATE_DIR" 2>/dev/null
# Namespace the marker by repo path so other projects never share a cooldown.
MARKER="$STATE_DIR/$(echo "$REPO_DIR" | tr -c 'a-zA-Z0-9' '_').last"

now=$(date +%s)
if [[ -f "$MARKER" ]]; then
  last=$(cat "$MARKER" 2>/dev/null || echo 0)
  [[ "$last" =~ ^[0-9]+$ ]] || last=0
  (( now - last < COOLDOWN_SECONDS )) && exit 0
fi

# --- gather state -------------------------------------------------------------
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
[[ -z "$branch" || "$branch" == "HEAD" ]] && exit 0

changed=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
staged=$(git diff --cached --name-only 2>/dev/null | wc -l | tr -d ' ')

# Commits ahead of the upstream, if an upstream exists.
ahead=0
if git rev-parse --abbrev-ref '@{upstream}' >/dev/null 2>&1; then
  ahead=$(git rev-list --count '@{upstream}..HEAD' 2>/dev/null || echo 0)
  has_upstream=1
else
  has_upstream=0
fi

# Commits on this branch not on main (only meaningful off main).
ahead_of_main=0
if [[ "$branch" != "main" && "$branch" != "master" ]]; then
  if git rev-parse --verify main >/dev/null 2>&1; then
    ahead_of_main=$(git rev-list --count main..HEAD 2>/dev/null || echo 0)
  fi
fi

COMMIT_THRESHOLD=3

# --- decide: first match wins, at most one suggestion -------------------------
msg=""
ctx=""

if [[ "$branch" == "main" || "$branch" == "master" ]]; then
  if (( changed > 0 )); then
    msg="On ${branch} with ${changed} uncommitted file(s). main is protected -- work belongs on a feature branch."
    ctx="The user is working directly on ${branch} with ${changed} uncommitted file(s). Per docs/git-workflow.md, main is protected and work belongs on a short-lived feature branch. Ask whether to move this work onto a feature/<phase>-<slug> branch. If they agree, use the /git-flow skill to create it (the changes carry over on checkout)."
  fi
elif (( changed >= COMMIT_THRESHOLD )); then
  msg="${changed} uncommitted file(s) on ${branch}. Worth committing this unit of work?"
  ctx="There are ${changed} uncommitted file(s) on branch ${branch}. Ask the user whether to commit now. If they agree, invoke the /git-flow skill to stage and write a Conventional Commits message derived from the actual diff (type(scope): subject, body explaining why)."
elif (( staged > 0 )); then
  msg="${staged} file(s) staged on ${branch} but not committed."
  ctx="There are ${staged} staged but uncommitted file(s) on ${branch}. Ask whether to commit them, and use /git-flow if the user agrees."
elif (( changed > 0 )); then
  # Uncommitted work below the commit threshold: mid-edit. Say nothing --
  # suggesting a push here would be wrong advice and pure noise.
  :
elif (( has_upstream == 1 && ahead > 0 )); then
  msg="${ahead} commit(s) on ${branch} not pushed to origin."
  ctx="Branch ${branch} is ${ahead} commit(s) ahead of its upstream. Ask whether to push. If the branch has a complete unit of work, also ask about opening a PR -- note the gh CLI must be installed first."
elif (( has_upstream == 0 && ahead_of_main > 0 )); then
  msg="${branch} has ${ahead_of_main} commit(s) and no upstream. Push and open a PR?"
  ctx="Branch ${branch} has ${ahead_of_main} commit(s) not on main and no upstream set. Ask whether to push with -u and open a PR via /git-flow. The gh CLI is required for the PR step and may not be installed."
fi

[[ -z "$msg" ]] && exit 0

echo "$now" > "$MARKER" 2>/dev/null

# --- emit ---------------------------------------------------------------------
# Escape for JSON: backslashes first, then double quotes.
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

printf '{"systemMessage":"git: %s","hookSpecificOutput":{"hookEventName":"Stop","additionalContext":"%s"}}\n' \
  "$(esc "$msg")" "$(esc "$ctx")"

exit 0
