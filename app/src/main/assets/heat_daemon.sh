#!/system/bin/sh
# HeatRadar Daemon - Lightweight process monitor
# Runs as shell UID to collect real CPU/memory/temp/freq data with minimal overhead

APP_PACKAGE="com.example.heatradar"
OUTPUT_DIR="/sdcard/Android/data/${APP_PACKAGE}/files"
TOP_OUTPUT="${OUTPUT_DIR}/top_output.txt"
PID_FILE="${OUTPUT_DIR}/daemon.pid"
STATUS_FILE="${OUTPUT_DIR}/daemon.status"
STOP_FILE="${OUTPUT_DIR}/daemon.stop"
INTERVAL=2
TMP_OUTPUT="/data/local/tmp/heat_top_tmp.txt"
SYS_OUTPUT="/data/local/tmp/heat_sys_tmp.txt"

dlog() {
    /system/bin/log -t "HeatDaemon" "$1"
}

write_status() {
    echo "pid=$$ time=$(date +%s) status=$1" > "$STATUS_FILE" 2>/dev/null
}

cleanup() {
    write_status "stopped"
    rm -f "$PID_FILE" "$STOP_FILE" "$TMP_OUTPUT" "$SYS_OUTPUT" 2>/dev/null
    exit 0
}

trap cleanup HUP INT TERM

echo $$ > "$PID_FILE" 2>/dev/null
write_status "running"

CPU_CORES=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
[ -z "$CPU_CORES" ] || [ "$CPU_CORES" -lt 1 ] && CPU_CORES=8
dlog "started pid=$$ cores=$CPU_CORES"

# Read /proc/stat, return "total idle"
read_cpu() {
    awk '/^cpu / { t=0; for(i=2;i<=NF;i++) t+=$i; print t, $5+$6 }' /proc/stat
}

# Read thermal zones, output: type1=temp1 type2=temp2 ...
read_temps() {
    _out=""
    for _tz in /sys/class/thermal/thermal_zone*; do
        [ -d "$_tz" ] || continue
        _type=$(cat "$_tz/type" 2>/dev/null)
        _temp=$(cat "$_tz/temp" 2>/dev/null)
        [ -z "$_type" ] || [ -z "$_temp" ] && continue
        # Convert to milli-celsius: some kernels output millidegree, others degree
        _temp=${_temp:-0}
        case "${_temp}" in
            ''|*[!0-9-]*) _temp=0 ;;
        esac
        # If value > 1000 it's millidegree, else degree
        if [ "${_temp#-}" -gt 1000 ] 2>/dev/null; then
            _temp=$((_temp / 1000))
        fi
        _out="${_out}${_type}=${_temp} "
    done
    echo "$_out"
}

# Read per-core cpu frequencies (kHz), output: f0 f1 f2 ...
read_freqs() {
    _out=""
    _i=0
    while [ "$_i" -lt "$CPU_CORES" ]; do
        _f=0
        if [ -f "/sys/devices/system/cpu/cpu${_i}/cpufreq/scaling_cur_freq" ]; then
            _f=$(cat "/sys/devices/system/cpu/cpu${_i}/cpufreq/scaling_cur_freq" 2>/dev/null)
            _f=${_f:-0}
            case "${_f}" in
                ''|*[!0-9]*) _f=0 ;;
            esac
        fi
        _out="${_out}${_f} "
        _i=$((_i + 1))
    done
    echo "$_out"
}

# Read memory info from /proc/meminfo, output key fields
read_mem() {
    awk '
        /^MemTotal:/     { mt=$2 }
        /^MemFree:/      { mf=$2 }
        /^MemAvailable:/ { ma=$2 }
        /^Cached:/       { mc=$2 }
        /^Buffers:/      { mb=$2 }
        /^SwapTotal:/    { st=$2 }
        /^SwapFree:/     { sf=$2 }
        /^SwapCached:/   { sc=$2 }
        END {
            printf "MemTotal=%d MemFree=%d MemAvailable=%d Cached=%d Buffers=%d SwapTotal=%d SwapFree=%d SwapCached=%d",
                mt+0, mf+0, ma+0, mc+0, mb+0, st+0, sf+0, sc+0
        }
    ' /proc/meminfo
}

# Read GPU busy % for Qualcomm Adreno
read_gpu() {
    _busy_pct=""
    if [ -f "/sys/class/kgsl/kgsl-3d0/gpubusy" ]; then
        _busy=$(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)
        set -- $_busy
        _b=${1:-0}
        _a=${2:-0}
        if [ "$_a" -gt 0 ] 2>/dev/null; then
            _busy_pct=$(awk -v b="$_b" -v a="$_a" 'BEGIN{printf "%.0f", (b/a)*100}')
        fi
    fi
    _gpu_clk=""
    if [ -f "/sys/class/kgsl/kgsl-3d0/gpuclk" ]; then
        _gpu_clk=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null)
        _gpu_clk=${_gpu_clk:-0}
    fi
    echo "gpu_busy=${_busy_pct:-0} gpu_clk=${_gpu_clk:-0}"
}

while true; do
    [ -f "$STOP_FILE" ] && cleanup

    set -- $(read_cpu)
    T1=$1; I1=$2
    sleep 0.15
    set -- $(read_cpu)
    T2=$1; I2=$2

    TD=$((T2 - T1))
    ID=$((I2 - I1))
    CPU_PCT="0.0"
    if [ "$TD" -gt 0 ]; then
        CPU_PCT=$(awk -v u=$((TD - ID)) -v t=$TD 'BEGIN{printf "%.1f", u*100/t}')
    fi

    # Collect system metrics
    _temps=$(read_temps)
    _freqs=$(read_freqs)
    _mem=$(read_mem)
    _gpu=$(read_gpu)

    if top -n 1 -b -q -o PID,USER,%CPU,RSS,NAME > "$TMP_OUTPUT" 2>/dev/null; then
        {
            echo "HR_CPU total=${CPU_PCT}% cores=${CPU_CORES}"
            echo "HR_TEMP ${_temps}"
            echo "HR_FREQ ${_freqs}"
            echo "HR_MEM ${_mem}"
            echo "HR_GPU ${_gpu}"
            cat "$TMP_OUTPUT"
        } > "$TOP_OUTPUT" 2>/dev/null
        write_status "running"
    else
        write_status "error"
    fi

    sleep $INTERVAL
done
