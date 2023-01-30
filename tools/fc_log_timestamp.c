// Based on https://github.com/firecracker-microvm/firecracker/blob/main/resources/tests/init.c

// Notice that the boot timer only works if Firecracker was started with `--boot-timer` and
// boot timer support was removed entirely when restoring from a snapshot (see:
// https://github.com/firecracker-microvm/firecracker/pull/2078).
#include <sys/types.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

// Base address values are defined in arch/src/lib.rs as arch::MMIO_MEM_START.
// Values are computed in arch/src/<arch>/mod.rs from the architecture layouts.
// Position on the bus is defined by MMIO_LEN increments, where MMIO_LEN is
// defined as 0x1000 in vmm/src/device_manager/mmio.rs.
#ifdef __x86_64__
#define MAGIC_MMIO_SIGNAL_GUEST_BOOT_COMPLETE 0xd0000000
#endif
#ifdef __aarch64__
#define MAGIC_MMIO_SIGNAL_GUEST_BOOT_COMPLETE 0x40000000
#endif

#define MAGIC_VALUE_SIGNAL_GUEST_BOOT_COMPLETE 123

int main () {
   int fd = open("/dev/mem", (O_RDWR | O_SYNC | O_CLOEXEC));
   int mapped_size = getpagesize();

   char *map_base = mmap(NULL,
        mapped_size,
        PROT_WRITE,
        MAP_SHARED,
        fd,
        MAGIC_MMIO_SIGNAL_GUEST_BOOT_COMPLETE);

   *map_base = MAGIC_VALUE_SIGNAL_GUEST_BOOT_COMPLETE;
   msync(map_base, mapped_size, MS_ASYNC);
}
