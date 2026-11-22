/*
 * libysmboot.so —— 用一个 LD_PRELOAD 库同时完成两件事
 *   ① constructor 里给 libc.so.6 的 verdef 链尾补一个 GLIBC_2.18
 *   ② 自己实现并导出 __cxa_thread_atexit_impl@GLIBC_2.18
 *
 * 为什么这样就够了：
 *   LD_PRELOAD 的库在全局符号查找链里排在 libc 之前，
 *   YSM native 找 __cxa_thread_atexit_impl 时第一个命中的就是这里，
 *   版本和符号同时对上。不需要改 l_searchlist。
 */
#define _GNU_SOURCE
#include <link.h>
#include <elf.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stddef.h>
#include <stdlib.h>
#include <pthread.h>

static void say(const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    if (n) { ssize_t r = write(2, s, n); (void)r; }
}

static int ends_with(const char *s, const char *t) {
    size_t ls = 0, lt = 0;
    while (s[ls]) ls++;
    while (t[lt]) lt++;
    if (lt > ls) return 0;
    for (size_t i = 0; i < lt; i++)
        if (s[ls - lt + i] != t[i]) return 0;
    return 1;
}

static unsigned long elf_hash(const char *s) {
    unsigned long h = 0, g;
    while (*s) {
        h = (h << 4) + (unsigned char)*s++;
        if ((g = h & 0xf0000000UL)) h ^= g >> 24;
        h &= ~g;
    }
    return h;
}

/* ---------------- ① verdef 注入 ---------------- */
static Elf64_Dyn  *g_dyn;
static Elf64_Addr  g_base;

static int find_libc_cb(struct dl_phdr_info *info, size_t sz, void *data) {
    (void)sz; (void)data;
    const char *n = info->dlpi_name;
    if (!n || !n[0]) return 0;
    if (!ends_with(n, "libc.so.6")) return 0;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC)
            g_dyn = (Elf64_Dyn *)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    g_base = info->dlpi_addr;
    return g_dyn ? 1 : 0;
}

static int inject_verdef(void) {
    static int done = 0;
    if (done) return 0;

    g_dyn = 0; g_base = 0;
    if (dl_iterate_phdr(find_libc_cb, 0) != 1) return -1;

    Elf64_Addr strtab = 0, verdef = 0;
    Elf64_Word *vnum = 0;
    for (Elf64_Dyn *d = g_dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_STRTAB)
            strtab = (d->d_un.d_ptr < g_base) ? (g_base + d->d_un.d_ptr) : d->d_un.d_ptr;
        else if (d->d_tag == DT_VERDEF)
            verdef = (d->d_un.d_ptr < g_base) ? (g_base + d->d_un.d_ptr) : d->d_un.d_ptr;
        else if (d->d_tag == DT_VERDEFNUM)
            vnum = (Elf64_Word *)&d->d_un.d_val;
    }
    if (!strtab || !verdef || !vnum) return -2;

    Elf64_Verdef *vd = (Elf64_Verdef *)verdef;
    while (1) {
        Elf64_Verdaux *a = (Elf64_Verdaux *)((char *)vd + vd->vd_aux);
        if (vd->vd_hash == (Elf64_Word)elf_hash("GLIBC_2.18")) {
            const char *nm = (const char *)(strtab + a->vda_name);
            if (nm && nm[0] == 'G' && nm[1] == 'L') { done = 1; return 0; }
        }
        if (!vd->vd_next) break;
        vd = (Elf64_Verdef *)((char *)vd + vd->vd_next);
    }

    unsigned long pg = (unsigned long)vd & ~0xfffUL;
    if (mprotect((void *)pg, 0x4000, PROT_READ | PROT_WRITE) != 0) return -3;

    void *np = 0;
    for (int i = 1; i < 64; i++) {
        void *hint = (void *)((pg + (unsigned long)i * 0x100000UL) & ~0xfffUL);
        void *m = mmap(hint, 0x2000, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (m == MAP_FAILED) continue;
        long dist = (long)((char *)m - (char *)vd);
        if (dist > 0 && dist < 0x60000000L) { np = m; break; }
        munmap(m, 0x2000);
    }
    if (!np) return -4;

    Elf64_Verdef *nv = (Elf64_Verdef *)np;
    nv->vd_version = 1;
    nv->vd_flags   = 0;
    nv->vd_ndx     = 0;      /* 不占用索引，避免和 GLIBC_PRIVATE(22) 冲突 */
    nv->vd_cnt     = 1;
    nv->vd_hash    = (Elf64_Word)elf_hash("GLIBC_2.18");
    nv->vd_aux     = (Elf64_Word)sizeof(Elf64_Verdef);
    nv->vd_next    = 0;

    char *nm = (char *)np + 0x40;
    const char *src = "GLIBC_2.18";
    for (int i = 0;; i++) { nm[i] = src[i]; if (!src[i]) break; }

    Elf64_Verdaux *aux = (Elf64_Verdaux *)((char *)nv + nv->vd_aux);
    aux->vda_name = (Elf64_Word)((Elf64_Addr)(unsigned long)nm - strtab);
    aux->vda_next = 0;

    vd->vd_next = (Elf64_Word)((char *)np - (char *)vd);

    done = 1;
    return 0;
}

/* ---------------- ② __cxa_thread_atexit_impl ---------------- */
typedef struct node {
    void (*fn)(void *);
    void *arg;
    struct node *next;
} node;

static pthread_key_t  g_key;
static pthread_once_t g_once  = PTHREAD_ONCE_INIT;
static int            g_ready = 0;

static void run_list(void *p) {
    node *n = (node *)p;
    while (n) {
        node *next = n->next;
        n->fn(n->arg);
        free(n);
        n = next;
    }
}

static void make_key(void) {
    if (pthread_key_create(&g_key, run_list) == 0) g_ready = 1;
}

int __cxa_thread_atexit_impl(void (*fn)(void *), void *arg, void *dso) {
    (void)dso;
    if (pthread_once(&g_once, make_key) != 0 || !g_ready) return -1;
    node *n = (node *)malloc(sizeof(node));
    if (!n) return -1;
    n->fn  = fn;
    n->arg = arg;
    n->next = (node *)pthread_getspecific(g_key);
    if (pthread_setspecific(g_key, n) != 0) { free(n); return -1; }
    return 0;
}

__attribute__((destructor)) static void fini(void) {
    if (!g_ready) return;
    run_list(pthread_getspecific(g_key));
    pthread_setspecific(g_key, 0);
}

/* ---------------- constructor ---------------- */
__attribute__((constructor)) static void boot(void) {
    int rc = inject_verdef();
    if (rc != 0) {
        say("[ysmboot] verdef injection failed, rc=");
        char b[4]; b[0] = (char)('0' + (rc < 0 ? -rc : rc) % 10); b[1] = '\n'; b[2] = 0;
        say(b);
    }
}
