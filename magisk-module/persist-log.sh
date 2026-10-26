#!/system/bin/sh
# The AntScreen log persister (the single source of truth; started at boot by post-fs-data.sh, with service.sh starting a fallback once it judges it dead).
#
# [#43 data-starvation fix, 2026-08-05] It used to persist **unfiltered device-wide logcat** (-r 1024 -n 4 = a 4MiB budget),
# while the App side's LogRepository keeps only AntScreen_* plus 4 crash tags → so nearly all of the 4MiB budget went on other processes' noise
# (measured on whyred: 62 minutes after boot, 0 AntScreen_ lines among 4MiB/28044 lines; that window covers about 29 minutes when idle and, extrapolated under load, only about 80 seconds —
# which is why a user's full log came back with 1 line, biting twice in #40 and #43). Changed to grep-filter before persisting:
# the same disk budget now holds hundreds of times longer, and the boot injection logs plus sign-in forensics are no longer squeezed out.
#
# The filter face is **word-for-word identical** to LogRepository's KEEP_RE / keep() (see KEEP below), and System.err is then decided a whole
# stack trace at a time by STDERR_GROUP, which LogRepository mirrors in Kotlin (see STDERR_GROUP). Changing this means changing that too.
#
# Rotation:
#  - A shift whenever a writer starts (antscreen.log→.1→…→.4), at boot and in service.sh's late fallback alike: no writer is running
#    (post-fs-data runs before any, service.sh only after judging the last one dead), so mv is safe. Every file therefore holds
#    exactly one writer's lines, and the boot_id that writer stamps on the file's first line speaks for all of them (see BOOT_ID).
#  - While running, the size is checked every 60s (checked before sleeping), and above 4MiB it uses **copy+truncate**. Never mv: the writer's fd follows the inode,
#    so after an mv it would keep writing into .1 and a new antscreen.log would never appear again; whereas >> is O_APPEND, so it writes to the new EOF after a truncate.
#  - antscreen.log.boot (the bootloop breadcrumb, written directly by post-fs-data) takes no part in the rotation.
#
# The fallback: an old toybox has no --line-buffered (the probe fails) → so it falls back to the original logcat -f -r/-n (unfiltered but working;
# do not let "better" kill "working"). Omitting --line-buffered will not do: grep block-buffers a pipe by default, so on a crash or a power cut
# the tail of the log (usually exactly the part being investigated) is stuck in the buffer and lost.

D=/data/adb/antscreen
LOGDIR="$D/log"
LOG="$LOGDIR/antscreen.log"
# NOTE — word-for-word identical to LogRepository's KEEP_RE / keep(); changing one means changing all three.
# AndroidRuntime takes E/F: the authoritative Java crash announcement `FATAL EXCEPTION` prints at E, while D/I are the `START RuntimeInit` /
# `Shutting down VM` printed by every shell command and every process start (46 lines in one whyred boot, of which 0 were crashes),
# which both eat the retention budget and get coloured orange/red in the user's log page, looking like a pile of AntScreen errors (#44, 2026-08-07).
# NOTE — libc takes **F only** and does not share AndroidRuntime's E/F tier (#44, 2026-08-11): the bulk of the noise is precisely at E —
#   measured across whyred's persistence chain, `E libc` was 342 lines, all 342 of them bionic warnings about misuse of the property API
#   (`The property name length for "…" is >= 31; please use __system_property_read_callback …`),
#   accounting for 341 of antscreen.log.2's 1060 lines (32%) and 85536 bytes on its own; and since every property name differs, the feedback body's repeat folding
#   (FeedbackIssueBody.foldRepeats groups by tag plus the body with digits stripped) cannot fold a single line, so one burst eats the whole 56000-character budget
#   and pushes out the event the user is reporting. A native crash's announcement line is itself `F libc: Fatal signal N` (all 45 lines on that device were F),
#   and the authoritative copy of the full stack is in /data/tombstones/ (the diagnostic header filters it by our keywords and takes it with the feedback), so taking F loses no evidence.
# **DEBUG has been removed**: it is debuggerd's whole-block dump, hundreds of lines at a time and produced by any App crash —
# measured on a KSU AVD at 68% of the retention face (15469/22824), burying our own 24 lines completely.
# System.err prints at W and cannot be narrowed by level; every System.err line passes this face and STDERR_GROUP narrows it by package name (see below).
# am_kill / am_anr / am_crash / am_proc_died come from the **events buffer** (see -b events above),
# and are the only live answer to "why did that process disappear" (carrying the process name, oom_adj and the reason); they print at I, so they cannot take the level narrowing the two crash tags above use.
# NOTE — lowmemorykiller is **not** in the unconditional group above (#44, 2026-08-09): its reclaim line is irreplaceable
#   (am_kill does not report LMK reclaims, while this line carries the oom_score_adj, the amount freed and the reason), but 69% of that tag is lmkd talking to itself
#   (81 of 118 measured lines: "Using in-kernel low memory killer interface", "process_mrelease … failed" and so on),
#   flooding every boot and coloured red and orange in the user's log page. So only lines beginning `lowmemorykiller: Kill ` are taken.
# NOTE — **System.err is persisted only when it carries com.tho493.antscreen** (#44, 2026-08-09; since #64 "it" is the whole trace, see the next note): the App side already keeps only the ones containing our package name when reading,
#   so persisting unconditionally means spending the retention budget on things guaranteed to be discarded. Measured on a KSU AVD (a telephony RIL crash loop): of antscreen.log's 6903 lines,
#   System.err was 6477 (94%), of which **0** contained antscreen — cutting retention by a whole order of magnitude and filling the log page's line cap as well
#   (a measured 41-second dump leaving only 421 of our own lines). Case sensitivity is deliberate (Java package names are case-sensitive, and an overall -i
#   would loosen the level position [VDIWEFA] too), matching LogRepository.keep word for word.
# NOTE — System.err is narrowed **a whole stack trace at a time**, not a line at a time (#64, 2026-09-17). printStackTrace writes one
#   log line per stack line, and only the frames carry our package: the line naming the exception (`java.lang.X: message`) and the
#   `Caused by:` lines do not. Deciding line by line kept the `at com.tho493.antscreen…` frames and dropped the one line saying what was
#   thrown. #64 arrived with two such traces, from :projection and system_server, and no way left to tell what they were.
#   So KEEP now lets every System.err line through and STDERR_GROUP (below) makes the package-name decision per trace.
KEEP=' ([VDIWEFA] (AntScreen|AntScreen_|am_kill|am_anr|am_crash|am_proc_died)|[EF] AndroidRuntime|F libc|[VDIWEFA] System\.err: |[VDIWEFA] lowmemorykiller: Kill )'
# NOTE — the per-trace decision (#64, 2026-09-17), mirrored line for line by LogRepository.stderrLinesKept and cross-checked by a unit
#   test that runs this very program under the host's awk against the Kotlin side. A trace is a run of consecutive System.err lines
#   from one pid/tid in the stream KEEP produces: any other line, a line from another thread, or a new trace head (a message not
#   starting with whitespace or `Caused by:`) starts the next one. A trace is kept whole once any of its lines contains our package.
#   - Lines are held only while the trace has not named our package yet. The moment it does, the held lines and everything after them
#     go out at once, so what reaches the disk is never delayed; and what is still held when the trace ends is exactly what the old
#     face threw away. The persister's durability is unchanged.
#   - At most 128 lines are held per trace, so a long third-party trace costs bounded memory. Past that, a trace that does name our
#     package later keeps its first 128 lines (the head is what was missing) plus everything from the naming line on.
#   - A message split over several lines, or a trace interleaved with another thread's System.err, splits into several traces, and
#     each part is decided on its own: the loss is back to what the old per-line face had, never below it.
#   - fflush() after every print: the output goes to a file, which awk block-buffers, and the tail of the log (usually the part being
#     investigated) must not sit in a buffer when the device dies.
STDERR_GROUP='
$6 != "System.err:" { open = ""; n = 0; print; fflush(); next }
{
  msg = substr($0, index($0, " System.err: ") + 13)
  key = $3 " " $4
  if (key != open || (msg !~ /^[ \t]/ && index(msg, "Caused by:") != 1)) { open = key; kept = 0; n = 0 }
  if (kept) { print; fflush(); next }
  if (index($0, "com.tho493.antscreen") > 0) {
    for (i = 1; i <= n; i++) print held[i]
    print
    fflush()
    kept = 1
    n = 0
    next
  }
  if (n < 128) held[++n] = $0
}'
# NOTE — awk is pinned to its absolute path for the same reason as grep below (a bare name resolves to the root manager's BusyBox
#   applet in standalone mode). Every Android release this module supports ships /system/bin/awk (checked on A13 and A16 devices),
#   but it is probed before use all the same, and a device without a working one keeps the per-line face (KEEP_LINE_ONLY) instead of
#   losing filtering altogether.
AWK=/system/bin/awk
# The pre-#64 face, kept word for word for that fallback: System.err narrowed by package name on the same line.
KEEP_LINE_ONLY=' ([VDIWEFA] (AntScreen|AntScreen_|am_kill|am_anr|am_crash|am_proc_died)|[EF] AndroidRuntime|F libc|[VDIWEFA] System\.err.*com\.tho493\.antscreen|[VDIWEFA] lowmemorykiller: Kill )'
# NOTE — grep must be pinned to toybox's absolute path: Magisk/ksud run module scripts with their bundled BusyBox in **standalone mode**,
# where a bare `grep` resolves to the busybox applet — which has no --line-buffered → so the probe fails → and it takes the raw fallback forever,
# meaning the filtering fix has no effect whatsoever on the real boot path (an adb shell verifies toybox's grep, a different environment; do not be fooled).
# An absolute path bypasses the applet lookup; /system is mounted by the post-fs-data stage, so toybox is available.
GREP=/system/bin/grep
# NOTE — the filter face version token (#44, 2026-08-09) is written **per file** on each log's first line, word-for-word identical to LogRepository.FILTER_FACE_TOKEN
#   (pinned by a cross-checking unit test). The App uses it to decide **per file** whether to grep again when reading:
#     with this line → that file already contains only lines within the retention face → so it just tails the last few thousand lines, at O(lines) cost regardless of how large the history is
#              (measured: a whole-file grep took 24.5 seconds versus milliseconds for a tail);
#     without   → a file upgraded from an older version / the raw fallback below / antscreen.log.boot → so the App greps, with a byte budget.
#   **It must not be a single directory-level marker**: the boot rotation demotes the old-format antscreen.log to antscreen.log.1 first and only then starts the new writer,
#   so a directory-level marker would cover the old-format rotations indiscriminately and have the App tail an old-format file (measured: 5 entries retrieved where there should have been 65).
#   Writing it as a log line has another benefit: it is itself a valid threadtime line, so it enters the log down the same parse path and testifies to "from which moment this file is filtered".
# NOTE — the 8 characters at the end of the token are **the first 8 of the SHA-256 of KEEP, a newline and STDERR_GROUP**, not a number picked by hand
#   (measured by mutation in the fourth review round; STDERR_GROUP joined the digest in #64, since it decides what a file holds just as KEEP does).
#   Changing either means changing it too (with the Kotlin side's FILTER_FACE_TOKEN in step), and a unit test computes the digest and cross-checks, going red the moment one is missed.
#   Why it must be embedded: with a hand-maintained version number sitting beside it, "the face changed but the number did not" leaves the whole suite green —
#   and then a wide-face file stamped by the old writer collides with the new face's number, the App takes the fast path and tails it, and the budget is again eaten by lines the reader is about to discard.
#   The leading v<N> is only a human-readable prefix and getting it wrong does not affect correctness; the digest is the criterion.
FACE_TOKEN='as-filter-face=v5-402f5cd4'
# NOTE — the token for a file written by the per-line fallback (#64). It must not be FACE_TOKEN: the App tails a FACE_TOKEN file as
#   already decided per trace. It still goes through write_face_line, so the file keeps its boot stamp; the App reads the stamp from any
#   `as-filter-face=` token and takes the slow path for this one, re-filtering it with the face it reads with.
LINE_ONLY_TOKEN='as-filter-face=line-only'
WRITER_TOKEN="$FACE_TOKEN"
# NOTE — the boot this writer runs in, stamped beside the token on the first line of every file it writes ([#61 review], 2026-09-11).
#   The App used to infer which lines belong to the current boot: from which file a line sat in (wrong once antscreen.log rotates by size
#   inside a boot), then from this script's start event (wrong when no writer ran this boot, when only the late fallback ran, and
#   when two boots on an unsynced clock log the same event text). Timestamps cannot settle it (no year, and 1970 dates before NTP).
#   The kernel's boot_id can: it is fixed for a boot and new on every boot, and the App reads the same file. LogRepository.parseHistory
#   holds the format; a missing id writes `boot_id=-`, which the App reads as no stamp at all, never as a guess.
BOOT_ID="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -dc '0-9a-f-')"
write_face_line() {
  printf '%s %d %d I AntScreen_Boot: persist-log.sh: %s boot_id=%s (lines below are pre-filtered by this writer)\n' \
    "$(date '+%m-%d %H:%M:%S.000')" "$$" "$$" "$WRITER_TOKEN" "${BOOT_ID:--}" >> "$LOG"
}
mkdir -p "$LOGDIR"

# Shift the chain by one (antscreen.log→.1→…→.4, discarding the oldest). Safe to call at boot (the writer has not started); while running it **must not** be used to move
# antscreen.log itself (see the copy+truncate explanation in the size rotation below), so shift_chain only moves .1 to .4.
shift_chain() {
  [ -f "$LOG.3" ] && mv -f "$LOG.3" "$LOG.4"
  [ -f "$LOG.2" ] && mv -f "$LOG.2" "$LOG.3"
  [ -f "$LOG.1" ] && mv -f "$LOG.1" "$LOG.2"
  return 0
}

# NOTE — every start shifts, not only `boot` ([#61 review], 2026-09-11). The late fallback used to append to antscreen.log as it was, and
#   when no writer had started earlier in the boot, that antscreen.log was the previous boot's: one file, two boots, and a first-line stamp
#   that would speak for both. A writer that died this boot loses nothing by the shift either: its file moves to .1 with its own
#   stamp. If the move fails, no writer starts, rather than one that mixes two writers' lines. The cost, accepted for that: a boot
#   whose fallback starts a second writer spends two of the chain's five files. (This note once named the bootloop fuse's rescue
#   boot as such a case. It is not one: no root manager runs a disabled module's service.sh, so that boot starts no writer at all.)
shift_chain
if [ -f "$LOG" ] && ! mv -f "$LOG" "$LOG.1"; then
  log -p e -t AntScreen_Boot "persist-log.sh: cannot rotate antscreen.log before starting a writer; refusing to mix two writers in one file"
  exit 1
fi

# The awk probe runs the real program over one line of ours and expects it back verbatim: a missing binary, a program this awk cannot
# parse, or a missing fflush() all fail it.
AWK_PROBE='01-01 00:00:00.000 1 1 W System.err: probe com.tho493.antscreen'
if [ -x "$GREP" ] && echo probe | "$GREP" --line-buffered -qE 'probe' 2>/dev/null; then
  if [ -x "$AWK" ] && [ "$(echo "$AWK_PROBE" | "$AWK" "$STDERR_GROUP" 2>/dev/null)" = "$AWK_PROBE" ]; then
    # NOTE — the first line must be written **before** starting the writer: the token has to be at the very front of the file, and the App only looks at the first 400 bytes.
    #   The raw fallback branch deliberately does not write it — that file has not been filtered and must not be taken by the App as safe to tail directly.
    write_face_line
    # $! is the last command of the pipeline, awk, which is the process writing the file: the size rotation below pauses exactly that one.
    logcat -b main -b crash -b events -v threadtime | "$GREP" --line-buffered -E "$KEEP" | "$AWK" "$STDERR_GROUP" >> "$LOG" &
    PERSIST_PID=$!
    MODE=filtered
  else
    WRITER_TOKEN="$LINE_ONLY_TOKEN"
    write_face_line
    logcat -b main -b crash -b events -v threadtime | "$GREP" --line-buffered -E "$KEEP_LINE_ONLY" >> "$LOG" &
    PERSIST_PID=$!
    MODE=filtered-line-only
  fi
else
  # NOTE — raw lines never land in a file that carries FACE_TOKEN: the shift above has already moved any earlier writer's antscreen.log
  #   to .1, so raw starts from a fresh, unmarked antscreen.log and the App does not take a mixed file for a filtered one. Raw files carry
  #   no boot stamp either; the App places them by their position in the chain (see LogRepository.parseHistory).
  logcat -b main -b crash -b events -v threadtime -r 1024 -n 4 -f "$LOG" >/dev/null 2>&1 &
  PERSIST_PID=$!
  MODE=raw-fallback
fi
echo "$PERSIST_PID" > "$D/logcat.pid"
# This line is both a startup trace and a guarantee that antscreen.log becomes non-empty quickly in filtered mode (service.sh's liveness check depends on it).
# NOTE — the raw fallback logs a **W**, not an I: it means the filtering did not take effect and retention has fallen back to "minutes", which must be visible in the user's export
# (an I passes release's minLevel but is very easily buried; this is a capability-degradation warning, so W is right).
if [ "$MODE" = "filtered" ]; then
  # This I is captured by its own grep pipeline and persisted → so it leaves a trace naturally.
  log -p i -t AntScreen_Boot "persist-log.sh: log persister started (mode=filtered pid=$PERSIST_PID caller=${1:-unknown})"
elif [ "$MODE" = "filtered-line-only" ]; then
  # A W for the same reason as the raw fallback's: a capability is missing and the export must say so. Unlike raw, this writer is
  # filtering, so its own grep captures the line and nothing needs to go straight to disk.
  log -p w -t AntScreen_Boot "persist-log.sh: log persister started without per-trace System.err grouping (no working $AWK); exception lines of our stack traces are not kept (mode=filtered-line-only pid=$PERSIST_PID caller=${1:-unknown})"
else
  # NOTE — the raw branch must **write straight to disk** and not merely emit to logd (CONFIRMED by the second adversarial review, 2026-08-05).
  # This line is precisely the warning that "filtering did not take effect and retention has fallen back to minutes", and in raw mode the 4MiB budget was measured to cover about 80 seconds —
  # it is written in the first second of boot = the oldest batch on the ring, so by the time the user exports hours later it has **necessarily been eaten by its own rotation**:
  # a trace on success and none on failure, exactly backwards. antscreen.log.boot takes no part in the rotation (see the header note), consistent with the existing pattern service.sh and post-fs-data.sh
  # use for recording critical events.
  log -p w -t AntScreen_Boot "persist-log.sh: log persister fell back to unfiltered mode (no grep --line-buffered); retention drops to minutes on chatty devices"
  printf '%s %s %s W AntScreen_Boot: log persister fell back to unfiltered mode (no grep --line-buffered); retention drops to minutes on chatty devices; boot_id=%s\n' \
    "$(date '+%m-%d %H:%M:%S.000')" "$$" "$$" "${BOOT_ID:--}" >> "$LOGDIR/antscreen.log.boot"
fi

# Both filtered modes write through a pipe with no size cap of their own; only raw has logcat's -r/-n.
if [ "$MODE" != "raw-fallback" ]; then
  (
    ticks=0
    while kill -0 "$PERSIST_PID" 2>/dev/null; do
      # NOTE — check before sleeping, on a 60s period (#43, third adversarial review round): filtered mode has no **continuous hard gate** like logcat's -r/-n,
      # so the size rests entirely on this watchdog. The original sleep 1800 was the loop body's first statement → leaving the first 30 minutes after boot unprotected,
      # with no upper bound on /data usage inside that window (the old raw path was a constant 5x1MiB). 60s squeezes the worst case down to megabytes.
      S=$(wc -c < "$LOG" 2>/dev/null | tr -d ' '); case "$S" in ''|*[!0-9]*) S=0 ;; esac
      if [ "$S" -gt 4194304 ]; then
        # If the writer keeps appending between the `cp` and the truncate, those lines are in neither .1 nor the truncated file.
        # Pausing the PID that actually writes the file (awk, or grep in the line-only mode) makes logcat wait on pipe back-pressure; resuming after the 4MiB copy completes
        # means not one line can land during the whole copy+truncate window. If pausing fails, the rotation is skipped: better temporarily over budget than silently losing evidence.
        if kill -STOP "$PERSIST_PID" 2>/dev/null; then
          ROTATE_TMP="$LOGDIR/.antscreen.log.rotate.$$"
          trap 'kill -CONT "$PERSIST_PID" 2>/dev/null; rm -f "$ROTATE_TMP"' EXIT INT TERM
          rm -f "$ROTATE_TMP"
          # Copy the current file to a temporary file in the same directory first and only shift the history after it succeeds. If cp fails because the disk is full,
          # the current file and .1 to .4 are all left as they were; the old implementation shifted before copying, wasting a generation of history on every retry.
          if cp -f "$LOG" "$ROTATE_TMP"; then
            shift_chain
            if mv -f "$ROTATE_TMP" "$LOG.1"; then
              # NOTE — use `true`, not `:` — `:` is a POSIX **special builtin** whose redirection failure makes a non-interactive shell **exit on the spot**,
              #   so the W warning in the else below could never be printed (dead code) and the whole rotation watchdog subshell went with it
              #   (after which that device never rotated by size again and the log grew unnoticed). `true` is an ordinary command whose failure only returns non-zero.
              #   This repo has stepped on the same trap once (#43's `: > "$T"`); do not plant it again.
              if true > "$LOG"; then
                # After truncating, the first line must be written again: the token went into .1 with the copy, and the new antscreen.log must also be marked filtered.
                write_face_line
                log -p i -t AntScreen_Boot "persist-log.sh: size rotation (paused writer; copy+truncate) at ${S}B"
              else
                log -p w -t AntScreen_Boot "persist-log.sh: size rotation truncate failed at ${S}B; current log kept and snapshot retained"
              fi
            else
              log -p w -t AntScreen_Boot "persist-log.sh: size rotation could not install the completed snapshot at ${S}B; current log kept"
            fi
          else
            rm -f "$ROTATE_TMP"
            log -p w -t AntScreen_Boot "persist-log.sh: size rotation copy failed at ${S}B; current log and history kept intact"
          fi
          kill -CONT "$PERSIST_PID" 2>/dev/null
          rm -f "$ROTATE_TMP"
          trap - EXIT INT TERM
        else
          log -p w -t AntScreen_Boot "persist-log.sh: could not pause writer for safe rotation at ${S}B; retrying later"
        fi
      fi
      # NOTE — a heartbeat, one line every 30 minutes (#44): without it, "the persister died" and "the device was quiet for a while" look identical in an export —
      # which is exactly the ambiguity #44 stalled on, wasting a whole round. 48 lines a day, cheap.
      ticks=$((ticks + 1))
      [ $((ticks % 30)) -eq 0 ] && log -p i -t AntScreen_Boot "persist-log.sh: persister alive (antscreen.log=${S}B)"
      sleep 60
    done
  ) &
fi
