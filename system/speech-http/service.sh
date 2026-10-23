#!/system/bin/sh
mod=${0%/*}
/data/adb/magisk/busybox nohup /data/adb/magisk/busybox ash "$mod/watch.sh" >/dev/null 2>&1 &
