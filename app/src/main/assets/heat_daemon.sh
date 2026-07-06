#!/system/bin/sh
# HeatRadar Daemon - Lightweight process monitor
# Started via ADB, runs as shell UID to collect real CPU/memory data

APP_PACKAGE="com.example.heatradar"
OUTPUT_DIR="/sdcard/Android/data/${APP_PACKAGE}/files"
TOP_OUTPUT="${OUTPUT_DIR}/top_output.txt"
PID_FILE="${OUTPUT_DIR}/daemon.pid"
STATUS_FILE="${OUTPUT_DIR}/daemon.status"
STOP_FILE="${OUTPUT_DIR}/daemon.stop"
INTERVAL=2
TMP_OUTPUT="/data/local/tmp/heat_top_tmp.txt"

dlog() {
    /system/bin/log -t "HeatDaemon" "$1"
}

write_status() {
    echo "pid=$$ time=$(date +%s) status=$1" > "$STATUS_FILE" 2>/dev/null
}

cleanup() {
    dlog "daemon stopping (pid=$$)"
    write_status "stopped"
    rm -f "$PID_FILE" 2>/dev/null
    rm -f "$STOP_FILE" 2>/dev/null
    rm -f "$TMP_OUTPUT" 2>/dev/null
    exit 0
}

trap cleanup HUP INT TERM

echo $$ > "$PID_FILE" 2>/dev/null
write_status "running"
dlog "daemon started (pid=$$, output=$TOP_OUTPUT)"

while true; do
    if [ -f "$STOP_FILE" ]; then
        dlog "stop file detected, exiting"
        cleanup
    fi

    top -n 1 -b -q -o PID,USER,%CPU,RSS,NAME > "$TMP_OUTPUT" 2>/dev/null

    if [ $? -eq 0 ] && [ -s "$TMP_OUTPUT" ]; then
        cat "$TMP_OUTPUT" > "$TOP_OUTPUT" 2>/dev/null
        write_status "running"
    else
        write_status "error"
    fi

    sleep $INTERVAL
done
