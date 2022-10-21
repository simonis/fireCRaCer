#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>

uint64_t PAGE_SIZE;

typedef struct {
  uint8_t swap_type : 5;
  uint64_t swap_ofset : 50;
} swap_entry;

typedef struct {
  uint64_t pfn : 55;
  unsigned int soft_dirty : 1;
  unsigned int exclusive : 1;
  unsigned int uffd_write_protect : 1;
  unsigned int zero : 3;
  unsigned int file_shared : 1;
  unsigned int swapped : 1;
  unsigned int present : 1;
} pm_entry;

typedef struct {
  int KPF_LOCKED : 1;
  int KPF_ERROR : 1;
  int KPF_REFERENCED : 1;
  int KPF_UPTODATE : 1;
  int KPF_DIRTY : 1;
  int KPF_LRU : 1;
  int KPF_ACTIVE : 1;
  int KPF_SLAB : 1;
  int KPF_WRITEBACK : 1;
  int KPF_RECLAIM : 1;
  int KPF_BUDDY : 1;
  int KPF_MMAP : 1;
  int KPF_ANON : 1;
  int KPF_SWAPCACHE : 1;
  int KPF_SWAPBACKED : 1;
  int KPF_COMPOUND_HEAD : 1;
  int KPF_COMPOUND_TAIL : 1;
  int KPF_HUGE : 1;
  int KPF_UNEVICTABLE : 1;
  int KPF_HWPOISON : 1;
  int KPF_NOPAGE : 1;
  int KPF_KSM : 1;
  int KPF_THP : 1;
  int KPF_OFFLINE : 1;
  int KPF_ZERO_PAGE : 1;
  int KPF_IDLE : 1;
  int KPF_PGTABLE : 1;
} kernel_page_flags;

void scan_pagemap(int pagemap, uint64_t start, uint64_t end) {
  while (start < end ) {
    pm_entry entry;
    size_t count;
    if ((count = pread(pagemap, &entry, sizeof(pm_entry), (start / PAGE_SIZE) * sizeof(pm_entry))) != sizeof(pm_entry)) {
      if (errno) {
        perror("Can't read from pagemap");
        return;
      }
    }
    if (entry.present) {
      printf("p %#018lx %#018lx %d %d\n", start, PAGE_SIZE * entry.pfn, entry.exclusive, entry.file_shared);
    }
    start += PAGE_SIZE;
  }
}

void scan_pageflags(int pageflags, uint64_t start, uint64_t end) {
  while (start < end ) {
    kernel_page_flags entry;
    size_t count;
    if ((count = pread(pageflags, &entry, sizeof(uint64_t), (start / PAGE_SIZE) * sizeof(pm_entry))) != sizeof(pm_entry)) {
      if (errno) {
        perror("Can't read from pagemap");
        return;
      }
    }
    if (1) {
      printf("p %#018lx %#018lx\n", start, *(long*)&entry);
    }
    start += PAGE_SIZE;
  }
}

int main(int argc, char *argv[]) {

  assert(sizeof(pm_entry) == 8);
  PAGE_SIZE = sysconf(_SC_PAGE_SIZE);

  if(argc != 2) {
    printf("Usage: %s <pid>\n", argv[0]);
    return 1;
  }

  if (!strcmp("kernel", argv[1])) {
    goto kernel;
  }
  errno = 0;
  pid_t pid = (pid_t)strtoul(argv[1], NULL, 0);
  if (errno != 0) {
    perror("First argument must be a pid");
    return errno;
  }

  char maps_file[PATH_MAX];
  char pagemap_file[PATH_MAX];
  char exe_link[PATH_MAX];
  char executable[PATH_MAX];
  snprintf(maps_file, sizeof(maps_file), "/proc/%s/maps", argv[1]);
  snprintf(pagemap_file, sizeof(maps_file), "/proc/%s/pagemap", argv[1]);
  snprintf(exe_link, sizeof(maps_file), "/proc/%s/exe", argv[1]);
  ssize_t len = readlink(exe_link, executable, PATH_MAX - 1);
  if (len >= 0) {
    executable[len] = '\0';
  } else {
    executable[0] = '\0';
  }
  FILE *maps = fopen(maps_file, "r");
  int pagemap = open(pagemap_file, O_RDONLY);
  if (maps == NULL || pagemap == -1) {
    perror("Cant open maps or pagemap file");
    return errno;
  }
  printf("= %d %s\n", pid, executable);

  len = PATH_MAX;
  char *line = (char*)malloc(len);
  while (getline(&line, &len, maps) != -1) {
    uint64_t start, end;
    sscanf(line, "%lx-%lx ", &start, &end);
    char *last = rindex(line, ' ');
    char *nl = rindex(line, '\n');
    char *pathname;
    if (nl && nl > last) {
      *nl = '\0';
      pathname = last + 1;
    } else {
      pathname = "";
    }
    printf("v %#018lx %#018lx %s\n", start, end, pathname);
    scan_pagemap(pagemap, start, end);
  }

 kernel:
  printf("= %d %s\n", 0, "kernel");
  FILE *iomap = fopen("/proc/iomem", "r");
  int pageflags = open("/proc/kpageflags", O_RDONLY);
  if (pageflags == -1) {
    perror("Cant open /proc/kpageflags file");
    return errno;
  }
  while (getline(&line, &len, iomap) != -1) {
    uint64_t start, end;
    if (line[0] != ' ' && (strstr(line, "System RAM") || strstr(line, "Reserved"))) {
      sscanf(line, "%lx-%lx ", &start, &end);
      printf("v %#018lx %#018lx\n", start, end);
      scan_pageflags(pageflags, start, end);
    }
  }
}
