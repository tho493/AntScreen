#!/system/bin/sh
# The AntScreen module's service.sh — persist logcat to disk from boot onwards.

MODDIR=${0%/*}
LOGDIR=/data/adb/antscreen/log
BOOT_ID="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -dc '0-9a-f-')"
mkdir -p "$LOGDIR"
magiskpolicy --live "allow { appdomain untrusted_app untrusted_app_all system_server shell } default_android_service service_manager { add find }" 2>/dev/null

if kill -0 "$(cat /data/adb/antscreen/logcat.pid 2>/dev/null)" 2>/dev/null; then
  log -p i -t AntScreen_Boot "service.sh: log persister already running from post-fs-data (pid=$(cat /data/adb/antscreen/logcat.pid))"
else
  sh "$MODDIR/persist-log.sh" late
  sleep 2
  if kill -0 "$(cat /data/adb/antscreen/logcat.pid 2>/dev/null)" 2>/dev/null; then
    log -p w -t AntScreen_Boot "service.sh: post-fs-data persister not alive -> started fallback"
  else
    log -p e -t AntScreen_Boot "service.sh: log persister FAILED to start -> boot injection logs will be lost"
    printf '%s %s %s E AntScreen_Boot: log persister failed to start; boot_id=%s\n' \
      "$(date '+%m-%d %H:%M:%S.000')" "$$" "$$" "${BOOT_ID:--}" >> "$LOGDIR/antscreen.log.boot"
  fi
fi

# Wait for system to complete boot
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done

touch /data/adb/antscreen/booted_ok
rm -f /data/adb/antscreen/bootfail

# Configure WindowManager Letterbox Center & Resizable Activities
cmd window set-letterbox-style --horizontalPositionMultiplier 0.5 --defaultPositionForHorizontalReachability center --persistentPositionForHorizontalReachability center 2>/dev/null
settings put global development_force_resizable_activities 1 2>/dev/null

# Launch AntScreen Root Control Daemon
if [ -f /data/adb/modules/antscreen/payload.apk ]; then
    CLASSPATH=/data/adb/modules/antscreen/payload.apk app_process /system/bin com.tho493.antscreen.engine.MirrorPayload system_server >/dev/null 2>&1 &
fi
