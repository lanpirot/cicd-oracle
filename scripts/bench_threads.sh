#!/usr/bin/env bash
#
# Benchmark: parallel variant throughput vs thread count.
# Runs the "parallel" mode on javassist/da39aa80 twice per thread count
# (cool vs warm), with 30s cooldown between thread counts.
#
set -euo pipefail

MERGE="da39aa805002d3b090d7f93410adce36f1e97d84"
BENCH_DIR="/home/lanpirot/data/bruteforcemerge/bench_threads"
CP="target/classes:target/dependency/*"
THREAD_COUNTS=(20 21 22 23 24 25 26 27 28)

cd /home/lanpirot/projects/merge++/cicd-oracle/cicd-oracle

mkdir -p "$BENCH_DIR"
RESULTS="$BENCH_DIR/results_fine.csv"
echo "threads,run,variants,wall_seconds,median_build_seconds,throughput_var_per_s" > "$RESULTS"

for threads in "${THREAD_COUNTS[@]}"; do
    for run in 1 2; do
        TAG="t${threads}_r${run}"
        echo ""
        echo "================================================================"
        echo "  threads=$threads  run=$run  ($(date +%H:%M:%S))"
        echo "================================================================"

        java -DmaxThreads="$threads" \
             -DbenchDir="$BENCH_DIR" \
             -DbenchTag="$TAG" \
             -cp "$CP" ch.unibe.cs.mergeci.experiment.ThreadBenchmark 2>&1 | \
             grep -E "\[budget|Summary|BENCH|\[overlay\]|best:"

        # Extract metrics from JSON
        JSON="$BENCH_DIR/parallel_$TAG/$MERGE.json"
        if [ -f "$JSON" ]; then
            python3 -c "
import json, statistics
d = json.load(open('$JSON'))
variants = d.get('variants', [])
threads_actual = d.get('threads', 1)
wall = d.get('variantsExecutionTimeSeconds', 0)
times = [v['ownExecutionSeconds'] for v in variants
         if v.get('variantIndex', 0) > 0
         and v.get('ownExecutionSeconds') is not None
         and not v.get('timedOut', False)]
n = len(variants) - 1  # exclude baseline idx=0 if present
med = statistics.median(times) if times else 0
tp = n / wall if wall > 0 else 0
print(f'  => threads={threads_actual}  variants={n}  wall={wall}s  median={med:.1f}s  throughput={tp:.2f} var/s')
with open('$RESULTS', 'a') as f:
    f.write(f'{threads_actual},{$run},{n},{wall},{med:.1f},{tp:.2f}\n')
"
        else
            echo "  => FAILED: no JSON output"
        fi
    done
    if [ "$threads" != "${THREAD_COUNTS[-1]}" ]; then
        echo "  ... cooling down 30s ..."
        sleep 30
    fi
done

echo ""
echo "================================================================"
echo "  RESULTS SUMMARY"
echo "================================================================"
column -t -s, < "$RESULTS"
