#!/system/bin/sh
# The AntScreen bootloop fuse (a post-fs-data common to Magisk/KSU/APatch; it runs before zygote and system_server).
#
# The idea: increment a "failure count" early in every boot; service.sh clears it once we have "survived the injection window" (boot_completed plus 40s).
# A normal boot is therefore always cleared and never accumulates. If the injection makes system_server crash and restart repeatedly within that window, service.sh never gets to clear it,
# the count reaches the threshold → touch disable → and the next boot has Magisk skip this module → so the device boots clean rather than trapping the user in a restart loop.
# Once tripped it writes an auto_disabled marker, which the App's health check reads to tell the user "automatically disabled after repeated crashes, you can redeploy".

MODDIR=${0%/*}
D=/data/adb/antscreen
THRESHOLD=3
BOOT_ID="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -dc '0-9a-f-')"
mkdir -p "$D"
# Allow system_server execmem for Pine dynamic runtime hook
magiskpolicy --live "allow system_server system_server process execmem" 2>/dev/null

mkdir -p /data/user_de/0/com.tho493.antscreen/files

rm -f "$D/logcat.pid"

if [ -f "$D/booted_ok" ]; then
  rm -f "$D/booted_ok"
  N=0
else
  N=$(cat "$D/bootfail" 2>/dev/null || echo 0)
  case "$N" in ''|*[!0-9]*) N=0 ;; esac
  N=$((N + 1))
  echo "$N" > "$D/bootfail"
fi

if [ "$N" -ge 2 ]; then
  log -p w -t AntScreen_Bootloop "boot tick: failure-counter=$N/$THRESHOLD (a previous boot did not survive the injection window)"
  mkdir -p "$D/log"
  printf '%s %s %s W AntScreen_Bootloop: boot tick failure-counter=%s/%s (previous boot did not survive injection window); boot_id=%s\n' \
    "$(date '+%m-%d %H:%M:%S.000')" "$$" "$$" "$N" "$THRESHOLD" "${BOOT_ID:--}" >> "$D/log/antscreen.log.boot"
fi

if [ "$N" -ge "$THRESHOLD" ]; then
  touch "$MODDIR/disable"
  date '+%Y-%m-%d %H:%M:%S' > "$D/auto_disabled"
  mkdir -p "$D/log"
  printf '%s %s %s E AntScreen_Bootloop: auto-disabled after %s consecutive injection-period crashes; module skipped next boot, re-deploy to re-enable; boot_id=%s\n' \
    "$(date '+%m-%d %H:%M:%S.000')" "$$" "$$" "$N" "${BOOT_ID:--}" >> "$D/log/antscreen.log.boot"
  log -p e -t AntScreen_Bootloop "auto-disabled after $N consecutive injection-period crashes; module disabled next boot"
  exit 0
fi

mkdir -p "$D/log"
sh "$MODDIR/persist-log.sh" boot
