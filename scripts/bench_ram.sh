#!/usr/bin/env bash
#
# Benchmark: variant throughput vs available RAM.
# Fixes thread count at 23, inflates a RAM balloon to simulate reduced
# MemAvailable, runs the benchmark twice (cool/warm), then releases.
#
# Test points: target MemAvailable = 65, 60, 55, 50, 45, 40, 35 GB
# (current MemAvailable is ~71 GB, theoretical need is 23×2.3 ≈ 53 GB)
#
set -euo pipefail

MERGE="da39aa805002d3b090d7f93410adce36f1e97d84"
BENCH_DIR="/home/lanpirot/data/bruteforcemerge/bench_ram"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CP="target/classes:target/dependency/*"
THREADS=23
TARGET_AVAIL_GB=(65 60 55 50 45 40 35)

cd /home/lanpirot/projects/merge++/cicd-oracle/cicd-oracle

mkdir -p "$BENCH_DIR"
RESULTS="$BENCH_DIR/results.csv"
echo "target_avail_gb,balloon_gb,actual_avail_gb,run,variants,wall_seconds,median_build_seconds,throughput_var_per_s" > "$RESULTS"

# Read current MemAvailable
current_avail_kb=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
current_avail_gb=$(echo "scale=1; $current_avail_kb / 1024 / 1024" | bc)
echo "Current MemAvailable: ${current_avail_gb} GB"
echo ""

for target in "${TARGET_AVAIL_GB[@]}"; do
    # How much to eat: current - target (rounded down to integer GB)
    balloon_gb=$(echo "($current_avail_kb / 1024 / 1024) - $target" | bc)
    balloon_gb=${balloon_gb%.*}  # truncate to int

    if [ "$balloon_gb" -le 0 ]; then
        balloon_gb=0
    fi

    echo "================================================================"
    echo "  target=${target} GB free  balloon=${balloon_gb} GB  ($(date +%H:%M:%S))"
    echo "================================================================"

    # Start balloon in background
    BALLOON_PID=""
    if [ "$balloon_gb" -gt 0 ]; then
        python3 "$SCRIPT_DIR/ram_balloon.py" "$balloon_gb" < /dev/null &
        BALLOON_PID=$!
        # Wait for balloon to finish allocating (watch for its "holding" message)
        sleep 2
        for i in $(seq 1 60); do
            if grep -q "MemAvailable" /proc/meminfo; then
                actual_kb=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
                actual_gb=$(echo "scale=1; $actual_kb / 1024 / 1024" | bc)
                # If we're within 3 GB of target, balloon is inflated
                diff=$(echo "$actual_gb - $target" | bc)
                abs_diff=${diff#-}
                if [ "$(echo "$abs_diff < 5" | bc)" -eq 1 ]; then
                    break
                fi
            fi
            sleep 1
        done
        echo "  MemAvailable after balloon: ${actual_gb} GB"
    else
        actual_kb=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
        actual_gb=$(echo "scale=1; $actual_kb / 1024 / 1024" | bc)
    fi

    for run in 1 2; do
        TAG="ram${target}_r${run}"
        echo ""
        echo "  --- run=$run ---"

        # Refresh actual MemAvailable right before the run
        actual_kb=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
        actual_gb=$(echo "scale=1; $actual_kb / 1024 / 1024" | bc)

        java -DmaxThreads="$THREADS" \
             -DbenchDir="$BENCH_DIR" \
             -DbenchTag="$TAG" \
             -cp "$CP" ch.unibe.cs.mergeci.experiment.ThreadBenchmark 2>&1 | \
             grep -E "\[budget|Summary|BENCH|\[overlay\]|best:"

        JSON="$BENCH_DIR/parallel_$TAG/$MERGE.json"
        if [ -f "$JSON" ]; then
            python3 -c "
import json, statistics
d = json.load(open('$JSON'))
variants = d.get('variants', [])
wall = d.get('variantsExecutionTimeSeconds', 0)
times = [v['ownExecutionSeconds'] for v in variants
         if v.get('variantIndex', 0) > 0
         and v.get('ownExecutionSeconds') is not None
         and not v.get('timedOut', False)]
n = len(variants) - 1
med = statistics.median(times) if times else 0
tp = n / wall if wall > 0 else 0
print(f'  => avail=${actual_gb}GB  variants={n}  wall={wall}s  median={med:.1f}s  throughput={tp:.2f} var/s')
with open('$RESULTS', 'a') as f:
    f.write(f'${target},${balloon_gb},${actual_gb},{$run},{n},{wall},{med:.1f},{tp:.2f}\n')
"
        else
            echo "  => FAILED: no JSON output"
            echo "${target},${balloon_gb},${actual_gb},${run},FAIL,FAIL,FAIL,FAIL" >> "$RESULTS"
        fi
    done

    # Deflate balloon
    if [ -n "$BALLOON_PID" ] && kill -0 "$BALLOON_PID" 2>/dev/null; then
        kill "$BALLOON_PID" 2>/dev/null
        wait "$BALLOON_PID" 2>/dev/null || true
        echo "  balloon released"
    fi

    echo "  ... cooling down 30s ..."
    sleep 30
done

echo ""
echo "================================================================"
echo "  RESULTS SUMMARY"
echo "================================================================"
column -t -s, < "$RESULTS"
