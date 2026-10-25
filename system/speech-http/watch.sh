#!/data/adb/magisk/busybox ash
# Only bootstrap/recover the Java service; HTTP and speech run in the factory host.
# No management host, ADB session, always-running instrumentation server, or disk log.
mod=${0%/*}
bb=/data/adb/magisk/busybox
[ "$(getprop ro.product.model)" = XDH-29-B1 ] || exit 1
state=/dev/xiaodu-speech-http
mkdir -p "$state"; chmod 700 "$state"
exec 9>"$state/watch.lock"
"$bb" flock -n 9 || exit 0
echo $$ > "$state/watch.pid"
cleanup() {
    echo xiaodu-zhenweisi > /sys/power/wake_unlock 2>/dev/null
    rm -f "$state/watch.pid" "$state/cpu-held"
    for control_pid in $(pidof xiaodu-control); do kill "$control_pid" 2>/dev/null; done
}
trap cleanup EXIT
trap 'exit 0' TERM INT
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2 9>&-; done
while [ ! -f "$mod/disable" ] && [ ! -f "$mod/remove" ]; do
    # Register a small adapter in the factory's existing IPC provider. Native
    # alarms remain owned by the original engine; bootstrap never creates one.
    alarm_pid=$(pidof com.baidu.launcher)
    case "$alarm_pid" in ''|*[!0-9]*) ;; *)
        alarm_start=$("$bb" awk '{print $22}' "/proc/$alarm_pid/stat" 2>/dev/null)
        alarm_sha=$("$bb" sha256sum "$mod/speech-http.jar" | "$bb" awk '{print $1}')
        alarm_stamp="$alarm_pid:$alarm_start:$alarm_sha"
        if [ "$(cat "$state/alarm-host" 2>/dev/null)" != "$alarm_stamp" ]; then
            alarm_app=/data/data/com.baidu.launcher
            alarm_owner=$(stat -c '%u:%g' "$alarm_app" 2>/dev/null)
            case "$alarm_owner" in ''|*[!0-9:]*) ;; *)
                alarm_data=$alarm_app/files/xiaodu-control
                mkdir -p "$alarm_data";chmod 700 "$alarm_data"
                cp "$mod/speech-http.jar" "$alarm_data/speech-http.jar.new"
                chown "$alarm_owner" "$alarm_data" "$alarm_data/speech-http.jar.new"
                chmod 600 "$alarm_data/speech-http.jar.new"
                mv -f "$alarm_data/speech-http.jar.new" "$alarm_data/speech-http.jar"
                restorecon -R "$alarm_data" >/dev/null 2>&1
                "$bb" timeout -s TERM 10 "$mod/frida-inject" -p "$alarm_pid" -s "$mod/alarm-loader.js" >"$state/alarm-bootstrap.log" 2>&1 9>&-
                if "$bb" grep -q '"kind":"alarm_control_loaded"' "$state/alarm-bootstrap.log";then
                    echo "$alarm_stamp" > "$state/alarm-host";chmod 600 "$state/alarm-host"
                fi
                ;;
            esac
        fi
        ;;
    esac
    if ! pidof xiaodu-control >/dev/null 2>&1; then
        CLASSPATH="$mod/speech-http.jar" /system/bin/app_process /system/bin --nice-name=xiaodu-control local.xiaodu.speech.DeviceControl >/dev/null 2>&1 9>&- &
    fi
    if ! "$bb" wget -q -T 3 -O - http://127.0.0.1:8765/health 2>/dev/null | "$bb" grep -q '"service":"xiaodu-speech"'; then
        pid=$(pidof com.baidu.duershow.dcs)
        case "$pid" in ''|*[!0-9]*) sleep 5 9>&-; continue;; esac
        app=/data/data/com.baidu.duershow.dcs
        owner=$(stat -c '%u:%g' "$app" 2>/dev/null)
        case "$owner" in ''|*[!0-9:]*) sleep 5 9>&-; continue;; esac
        data=$app/files/xiaodu-http
        mkdir -p "$data"
        "$bb" cmp -s "$mod/speech-http.jar" "$data/speech-http.jar" || cp "$mod/speech-http.jar" "$data/speech-http.jar"
        chown -R "$owner" "$data"
        chmod 700 "$data"
        chmod 600 "$data/speech-http.jar"
        for dir in lib model; do
            mkdir -p "$data/$dir"; chmod 700 "$data/$dir"
            for file in "$mod/$dir/"*; do
                [ -f "$file" ] || continue
                target="$data/$dir/${file##*/}"
                "$bb" cmp -s "$file" "$target" || cp "$file" "$target"
                chown "$owner" "$target";chmod 600 "$target"
            done
            chown "$owner" "$data/$dir"
        done
        restorecon -R "$data" >/dev/null 2>&1
        "$bb" timeout -s TERM 20 "$mod/frida-inject" -e -p "$pid" -s "$mod/loader.js" >"$state/bootstrap.log" 2>&1 9>&-
        { date '+%Y-%m-%dT%H:%M:%S%z'; echo "dcs_pid=$pid"; } > "$state/last-bootstrap"
    fi
    voice=$("$bb" wget -q -T 3 -O - http://127.0.0.1:8765/v1/voice/status 2>/dev/null)
    case "$voice" in
        *'"state":"wake_ready"'*|*'"state":"wake_starting"'*|*'"state":"acknowledging"'*|*'"state":"listening"'*|*'"state":"realtime_'*)
            [ -f "$state/cpu-held" ] || { echo xiaodu-zhenweisi > /sys/power/wake_lock 2>/dev/null && touch "$state/cpu-held"; };;
        *) echo xiaodu-zhenweisi > /sys/power/wake_unlock 2>/dev/null;rm -f "$state/cpu-held";;
    esac
    sleep 30 9>&-
done
