#pragma once
// backend.h: shared GGML backend initialization
//
// All modules use the same pattern: load all backends, pick best GPU,
// keep CPU as fallback. This avoids duplicating init logic across
// qwen3.h, qwen3-lm.h, cond.h, dit.h, vae.h.

#include "ggml-backend.h"
#include "ggml.h"
#include "ggml-cpu.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>

#ifdef __ANDROID__
#include <sched.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/resource.h>
#include <sys/mman.h>
#endif

struct BackendPair {
    ggml_backend_t backend;
    ggml_backend_t cpu_backend;
    bool           has_gpu;
};

// Cached backend state (shared across all modules in the same binary)
static BackendPair g_backend_cache = {};
static int         g_backend_refs  = 0;

// ---------- Android big.LITTLE core affinity ----------
#ifdef __ANDROID__
// Detect big cores by reading cpuinfo_max_freq, pin current process to them.
// Returns the number of big cores found.
static int backend_pin_big_cores(void) {
    int max_freq = 0;
    int freqs[16] = {};
    int ncpu = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (ncpu > 16) ncpu = 16;

    for (int i = 0; i < ncpu; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (f) { fscanf(f, "%d", &freqs[i]); fclose(f); }
        if (freqs[i] > max_freq) max_freq = freqs[i];
    }

    // "Big" = anything above 80% of max freq (catches X4 + A720, excludes A520)
    int threshold = max_freq * 80 / 100;
    cpu_set_t mask;
    CPU_ZERO(&mask);
    int big_count = 0;
    for (int i = 0; i < ncpu; i++) {
        if (freqs[i] >= threshold) {
            CPU_SET(i, &mask);
            big_count++;
        }
    }
    if (big_count > 0) {
        sched_setaffinity(0, sizeof(mask), &mask);
        fprintf(stderr, "[Android] Pinned to %d big cores (threshold %d kHz)\n", big_count, threshold);
    }
    return big_count;
}
#endif

// Physical core count heuristic (logical / 2 for HT/SMT).
// Used for GGML CPU thread count: GEMM shares SIMD units across hyperthreads,
// so one thread per physical core is optimal.
static int backend_cpu_n_threads(void) {
#ifdef __ANDROID__
    // On Android big.LITTLE, use big core count (pinned via backend_pin_big_cores)
    static int cached = 0;
    if (cached > 0) return cached;
    int max_freq = 0;
    int freqs[16] = {};
    int ncpu = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (ncpu > 16) ncpu = 16;
    for (int i = 0; i < ncpu; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (f) { fscanf(f, "%d", &freqs[i]); fclose(f); }
        if (freqs[i] > max_freq) max_freq = freqs[i];
    }
    int threshold = max_freq * 80 / 100;
    int big = 0;
    for (int i = 0; i < ncpu; i++) if (freqs[i] >= threshold) big++;
    cached = big > 0 ? big : ncpu - 1;
    return cached;
#else
    int n = (int) std::thread::hardware_concurrency() - 1;
    return n > 0 ? n : 1;
#endif
}

// Standalone CPU backend via Registry API (DL-safe, no ggml-cpu.h needed).
// Sets thread count via proc address since ggml_backend_cpu_device_init_backend
// ignores its params string and always defaults to GGML_DEFAULT_N_THREADS (4).
// Returns NULL on failure.
static ggml_backend_t cpu_backend_new(int n_threads) {
    ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    ggml_backend_t     cpu     = NULL;
    if (cpu_dev) {
        cpu = ggml_backend_dev_init(cpu_dev, NULL);
    }
    if (!cpu) {
        cpu = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, NULL);
    }
    if (!cpu) {
        return NULL;
    }

    ggml_backend_dev_t dev = ggml_backend_get_device(cpu);
    ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : NULL;
    if (reg) {
        // Set thread count
        auto set_fn =
            (ggml_backend_set_n_threads_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads");
        if (set_fn) {
            set_fn(cpu, n_threads);
        }

        // Create threadpool with big-core affinity and high priority
        struct ggml_threadpool_params tp = ggml_threadpool_params_default(n_threads);
        tp.prio = GGML_SCHED_PRIO_HIGH;
#ifdef __ANDROID__
        tp.strict_cpu = true;
        tp.poll       = 50;  // moderate polling (reduces latency vs pure sleep)
        // Set cpumask to big cores only
        int max_freq = 0;
        int freqs[GGML_MAX_N_THREADS] = {};
        int ncpu = (int)sysconf(_SC_NPROCESSORS_CONF);
        if (ncpu > GGML_MAX_N_THREADS) ncpu = GGML_MAX_N_THREADS;
        for (int i = 0; i < ncpu; i++) {
            char path[128];
            snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
            FILE * f = fopen(path, "r");
            if (f) { fscanf(f, "%d", &freqs[i]); fclose(f); }
            if (freqs[i] > max_freq) max_freq = freqs[i];
        }
        int threshold = max_freq * 80 / 100;
        for (int i = 0; i < ncpu; i++) {
            tp.cpumask[i] = (freqs[i] >= threshold);
        }
#endif
        typedef void (*set_tp_fn_t)(ggml_backend_t, ggml_threadpool_t);
        auto set_tp_fn = (set_tp_fn_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_cpu_set_threadpool");
        if (set_tp_fn) {
            struct ggml_threadpool * tpool = ggml_threadpool_new(&tp);
            if (tpool) {
                set_tp_fn(cpu, tpool);
                fprintf(stderr, "[CPU] Threadpool: %d threads, prio=HIGH, poll=%d\n", n_threads, tp.poll);
            }
        }
    }
    return cpu;
}

// Initialize backends: load all available (CUDA, Metal, Vulkan...),
// pick the best one, keep CPU as fallback.
// label: log prefix, e.g. "DiT", "VAE", "LM"
// Subsequent calls reuse the same backend (single VMM pool).
static BackendPair backend_init(const char * label) {
    if (g_backend_refs > 0) {
        g_backend_refs++;
        fprintf(stderr, "[Load] %s backend: %s (shared)\n", label, ggml_backend_name(g_backend_cache.backend));
        return g_backend_cache;
    }

#ifdef __ANDROID__
    // First init: pin to big cores and request sustained performance
    backend_pin_big_cores();
    // Set high scheduling priority for compute threads
    setpriority(PRIO_PROCESS, 0, -10);
#endif

    ggml_backend_load_all();
    BackendPair bp = {};

    // GGML_BACKEND env var: force a specific device instead of auto-best.
    // Device names: CUDA0, Vulkan0, CPU, BLAS (see ggml_backend_dev_name).
    const char * force_backend = std::getenv("GGML_BACKEND");
    if (force_backend) {
        bp.backend = ggml_backend_init_by_name(force_backend, nullptr);
        if (!bp.backend) {
            fprintf(stderr, "[Load] FATAL: GGML_BACKEND=%s not found. Available:", force_backend);
            for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
                fprintf(stderr, " %s", ggml_backend_dev_name(ggml_backend_dev_get(i)));
            }
            fprintf(stderr, "\n");
            exit(1);
        }
    } else {
        bp.backend = ggml_backend_init_best();
    }
    if (!bp.backend) {
        fprintf(stderr, "[Load] FATAL: no backend available\n");
        exit(1);
    }
    bool best_is_cpu = (strcmp(ggml_backend_name(bp.backend), "CPU") == 0);
    int  n_threads   = backend_cpu_n_threads();
    if (best_is_cpu) {
        ggml_backend_free(bp.backend);
        bp.backend     = cpu_backend_new(n_threads);
        bp.cpu_backend = bp.backend;
    } else {
        bp.cpu_backend = cpu_backend_new(n_threads);
    }
    if (!bp.cpu_backend) {
        fprintf(stderr, "[Load] FATAL: failed to init CPU backend\n");
        exit(1);
    }
    bp.has_gpu = !best_is_cpu;
    fprintf(stderr, "[Load] %s backend: %s (CPU threads: %d)\n", label, ggml_backend_name(bp.backend), n_threads);

    g_backend_cache = bp;
    g_backend_refs  = 1;
    return bp;
}

// Release a backend reference. Frees GPU + CPU backends when refcount hits 0.
static void backend_release(ggml_backend_t backend, ggml_backend_t cpu_backend) {
    if (g_backend_refs <= 0) {
        return;
    }
    g_backend_refs--;
    if (g_backend_refs == 0) {
        if (backend && backend != cpu_backend) {
            ggml_backend_free(backend);
        }
        if (cpu_backend) {
            ggml_backend_free(cpu_backend);
        }
        g_backend_cache = {};
    }
}

// Create a scheduler from a backend pair.
// max_nodes: graph size hint (4096 for small models, 8192 for large)
// When a GPU is present, use its host buffer type for the CPU backend.
// Pinned memory lets the scheduler keep more ops on GPU instead of
// falling back to CPU with plain malloc.
static ggml_backend_sched_t backend_sched_new(BackendPair bp, int max_nodes) {
    ggml_backend_t             backends[2] = { bp.backend, bp.cpu_backend };
    ggml_backend_buffer_type_t bufts[2]    = { NULL, NULL };
    int                        n           = (bp.backend == bp.cpu_backend) ? 1 : 2;

    bufts[0] = ggml_backend_get_default_buffer_type(bp.backend);
    if (n == 2) {
        ggml_backend_dev_t         gpu_dev   = ggml_backend_get_device(bp.backend);
        ggml_backend_buffer_type_t host_buft = gpu_dev ? ggml_backend_dev_host_buffer_type(gpu_dev) : NULL;
        bufts[1] = host_buft ? host_buft : ggml_backend_get_default_buffer_type(bp.cpu_backend);
    }

    ggml_backend_sched_t sched = ggml_backend_sched_new(backends, bufts, n, max_nodes, false, true);
    if (!sched) {
        fprintf(stderr, "[Load] FATAL: failed to create scheduler\n");
        exit(1);
    }
    return sched;
}
