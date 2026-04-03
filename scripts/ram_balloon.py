#!/usr/bin/env python3
"""
RAM balloon — allocate and hold a specified amount of RAM.
Used by bench_ram.sh to simulate reduced MemAvailable.

Usage: python3 ram_balloon.py <GB>
Allocates <GB> gigabytes, touches every page, then waits for SIGTERM or stdin EOF.
"""
import sys, signal, mmap

def main():
    gb = int(sys.argv[1])
    if gb <= 0:
        print(f"[balloon] 0 GB requested, not allocating", flush=True)
        sys.stdin.readline()
        return

    size = gb * 1024 * 1024 * 1024
    print(f"[balloon] allocating {gb} GB...", flush=True)

    # mmap anonymous pages — more reliable than bytearray for large allocations
    mem = mmap.mmap(-1, size, mmap.MAP_PRIVATE | mmap.MAP_ANONYMOUS)

    # Touch every page (4K) to force physical allocation
    for offset in range(0, size, 4096):
        mem[offset] = 1

    # Read MemAvailable after allocation
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                avail_gb = int(line.split()[1]) / 1024 / 1024
                print(f"[balloon] holding {gb} GB — MemAvailable now: {avail_gb:.1f} GB", flush=True)
                break

    # Wait for signal or stdin close
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        sys.stdin.readline()
    except:
        pass

if __name__ == "__main__":
    main()
