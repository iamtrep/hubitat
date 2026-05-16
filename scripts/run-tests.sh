#!/usr/bin/env bash
# Copyright (c) 2025-2026 PJ
# SPDX-License-Identifier: MIT

#
# Top-level test runner. Discovers every `<project>/tests/test-*.sh`,
# `<project>/tests/test-*.js`, and `<project>/tests/test_*.py` under the
# repo, runs each, and prints a final summary.
#
# Per TESTING.md §2.4. Closes the "run everything before commit" gap.
#
# Conventions:
#   *.sh        →  bash <file> [@hubname]      (hub-driven integration test)
#   *.js        →  node <file>                 (pure JS unit test)
#   test_*.py   →  python3 -m pytest <file>    (pytest-style unit test)
#
# Opt-out: a test is skipped if its first 20 lines contain the marker
# `TEST-EXCLUDE` (in any comment form).
#
# Usage:
#   bash scripts/run-tests.sh                  # all tests, default hub from .hubitat.json
#   bash scripts/run-tests.sh @maison-pro      # forward @hubname to *.sh tests
#   bash scripts/run-tests.sh --list           # list discovered tests and exit
#   bash scripts/run-tests.sh --verbose        # stream each test's stdout/stderr live
#   bash scripts/run-tests.sh @hub --filter sadc  # only tests whose path matches "sadc"
#
# Exit codes (mirroring the per-test contract in TESTING.md §1.1).
# The ladder is regression > rig issue > all-green:
#   0 — every discovered test ran and passed.
#   1 — at least one test failed an assertion (child exit 1, or any
#       non-{0,2}). Takes priority over rig issues — a real regression
#       matters more than incomplete coverage.
#   2 — runner couldn't enumerate tests, OR at least one child exited 2
#       (rig/config/auth — couldn't run) and no child reported an
#       assertion failure. Coverage is incomplete; investigate the
#       [ERROR] list before trusting the [PASS]es. Returning 0 in this
#       case would let a single passing-by-luck test mask N rig failures.
#

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

HUB_NAME=""
LIST_ONLY=0
VERBOSE=0
FILTER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        @*)         HUB_NAME="$1"; shift ;;
        --list)     LIST_ONLY=1; shift ;;
        --verbose|-v) VERBOSE=1; shift ;;
        --filter)   FILTER="${2:-}"; shift 2 ;;
        --help|-h)
            sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Run with --help for usage." >&2
            exit 2 ;;
    esac
done

# ── ANSI colours ──────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    G="\033[32m"; R="\033[31m"; Y="\033[33m"; C="\033[36m"; D="\033[2m"; B="\033[1m"; X="\033[0m"
else
    G=""; R=""; Y=""; C=""; D=""; B=""; X=""
fi

# ── Discovery ─────────────────────────────────────────────────────────
ALL_TESTS=()
while IFS= read -r line; do
    ALL_TESTS+=("$line")
done < <(
    find "$PROJECT_ROOT" \
        -path "*/.claude/worktrees" -prune -o \
        -path "*/node_modules" -prune -o \
        -path "*/.git" -prune -o \
        -type f \( \
            -path "*/tests/test-*.sh" -o \
            -path "*/tests/test-*.js" -o \
            -path "*/tests/test_*.py" \
        \) -print | sort
)

if [[ ${#ALL_TESTS[@]} -eq 0 ]]; then
    echo "No tests discovered under $PROJECT_ROOT" >&2
    exit 2
fi

# Apply --filter and TEST-EXCLUDE
TESTS=()
SKIPPED=()
for path in "${ALL_TESTS[@]}"; do
    rel="${path#$PROJECT_ROOT/}"
    if [[ -n "$FILTER" && "$rel" != *"$FILTER"* ]]; then
        continue
    fi
    if head -n 20 "$path" 2>/dev/null | grep -q "TEST-EXCLUDE"; then
        SKIPPED+=("$rel")
        continue
    fi
    TESTS+=("$path")
done

if [[ $LIST_ONLY -eq 1 ]]; then
    echo "Discovered ${#TESTS[@]} tests:"
    for path in "${TESTS[@]}"; do
        echo "  ${path#$PROJECT_ROOT/}"
    done
    if [[ ${#SKIPPED[@]} -gt 0 ]]; then
        echo
        echo "Skipped (TEST-EXCLUDE): ${#SKIPPED[@]}"
        for rel in "${SKIPPED[@]}"; do echo "  $rel"; done
    fi
    exit 0
fi

if [[ ${#TESTS[@]} -eq 0 ]]; then
    echo "No tests matched filter '$FILTER'" >&2
    exit 2
fi

# ── Run ───────────────────────────────────────────────────────────────
echo -e "${B}Running ${#TESTS[@]} tests${X}${HUB_NAME:+ ${D}(hub: $HUB_NAME)${X}}"
if [[ ${#SKIPPED[@]} -gt 0 ]]; then
    echo -e "${D}Skipped (TEST-EXCLUDE): ${#SKIPPED[@]}${X}"
fi
echo

PASS_COUNT=0
FAIL_COUNT=0
ERROR_COUNT=0
FAIL_PATHS=()
ERROR_PATHS=()
RUNNER_START=$(date +%s)

run_one() {
    local path="$1"
    local rel="${path#$PROJECT_ROOT/}"
    local ext="${path##*.}"

    # Build invocation
    local -a cmd
    case "$ext" in
        sh) cmd=(bash "$path"); [[ -n "$HUB_NAME" ]] && cmd+=("$HUB_NAME") ;;
        js) cmd=(node "$path") ;;
        py) cmd=(python3 -m pytest -q --no-header "$path") ;;
        *)  echo -e "  ${Y}[SKIP]${X} $rel — unknown extension"; return 0 ;;
    esac

    local out_file
    out_file="$(mktemp)"
    local started ended elapsed ec=0

    started=$(date +%s)
    if [[ $VERBOSE -eq 1 ]]; then
        "${cmd[@]}" 2>&1 | tee "$out_file"
        ec=${PIPESTATUS[0]}
    else
        "${cmd[@]}" > "$out_file" 2>&1
        ec=$?
    fi
    ended=$(date +%s)
    elapsed=$((ended - started))

    if [[ $ec -eq 0 ]]; then
        PASS_COUNT=$((PASS_COUNT + 1))
        echo -e "  ${G}[PASS]${X} $rel ${D}(${elapsed}s)${X}"
    elif [[ $ec -eq 2 ]]; then
        # Per the §1.1 contract: exit 2 = "couldn't run at all" (rig/config/auth).
        # Track separately from assertion failures so the suite-level exit code
        # preserves the same three-way distinction.
        ERROR_COUNT=$((ERROR_COUNT + 1))
        ERROR_PATHS+=("$rel (exit 2)")
        echo -e "  ${Y}[ERROR]${X} $rel ${D}(${elapsed}s, exit 2 — could not run)${X}"
        if [[ $VERBOSE -eq 0 ]]; then
            sed 's/^/      /' "$out_file" | tail -n 40
        fi
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        FAIL_PATHS+=("$rel (exit $ec)")
        echo -e "  ${R}[FAIL]${X} $rel ${D}(${elapsed}s, exit $ec)${X}"
        if [[ $VERBOSE -eq 0 ]]; then
            sed 's/^/      /' "$out_file" | tail -n 40
        fi
    fi
    rm -f "$out_file"
}

for path in "${TESTS[@]}"; do
    run_one "$path"
done

RUNNER_END=$(date +%s)
TOTAL_ELAPSED=$((RUNNER_END - RUNNER_START))

# ── Summary ───────────────────────────────────────────────────────────
echo
SUMMARY="${B}=== ${PASS_COUNT}/${#TESTS[@]} passed${X}"
[[ $FAIL_COUNT  -gt 0 ]] && SUMMARY+=", ${R}${FAIL_COUNT} failed${X}"
[[ $ERROR_COUNT -gt 0 ]] && SUMMARY+=", ${Y}${ERROR_COUNT} could-not-run${X}"
SUMMARY+=" ${D}(${TOTAL_ELAPSED}s)${X}"
echo -e "$SUMMARY"

if [[ $FAIL_COUNT -gt 0 ]]; then
    echo
    echo -e "${R}Failed tests (assertion regressions):${X}"
    for f in "${FAIL_PATHS[@]}"; do
        echo "  - $f"
    done
fi
if [[ $ERROR_COUNT -gt 0 ]]; then
    echo
    echo -e "${Y}Could-not-run (rig/config/auth):${X}"
    for f in "${ERROR_PATHS[@]}"; do
        echo "  - $f"
    done
fi

# Suite exit code mirrors the §1.1 three-way contract.
# Assertion failures take priority — a real regression matters more than a
# rig issue. If nothing ran cleanly and the only signal is "couldn't run",
# exit 2 so an agent loop branches to rig/config triage instead of debugging
# nonexistent assertion regressions.
if (( FAIL_COUNT > 0 )); then
    exit 1
elif (( ERROR_COUNT > 0 )); then
    exit 2
else
    exit 0
fi
