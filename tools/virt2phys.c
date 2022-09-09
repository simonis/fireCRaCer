#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>

long PAGE_SIZE;

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

void scan_pagemap(int pagemap, pid_t pid, unsigned long start, unsigned long end) {
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
      printf("%lx %d %lx %d\n", (unsigned long)entry.pfn, pid, start, entry.exclusive);
    }
    start += PAGE_SIZE;
  }
}

int main(int argc, char *argv[]) {

  assert(sizeof(pm_entry) == 8);

  if(argc != 2) {
    printf("Usage: %s <pid>\n", argv[0]);
    return 1;
  }

  errno = 0;
  pid_t pid = (pid_t)strtoul(argv[1], NULL, 0);
  if (errno != 0) {
    perror("First argument must be a pid");
    return errno;
  }

  char maps_file[PATH_MAX], pagemap_file[PATH_MAX];
  snprintf(maps_file, sizeof(maps_file), "/proc/%s/maps", argv[1]);
  snprintf(pagemap_file, sizeof(maps_file), "/proc/%s/pagemap", argv[1]);
  FILE *maps = fopen(maps_file, "r");
  int pagemap = open(pagemap_file, O_RDONLY);
  if (maps == NULL || pagemap == -1) {
    perror("Cant open maps or pagemap file");
    return errno;
  }

  PAGE_SIZE = sysconf(_SC_PAGE_SIZE);
  size_t len = PATH_MAX;
  char *line = (char*)malloc(len);
  while(getline(&line, &len, maps) != -1) {
    unsigned long start, end;
    sscanf(line, "%lx-%lx ", &start, &end);
    scan_pagemap(pagemap, pid, start, end);
  }
}
