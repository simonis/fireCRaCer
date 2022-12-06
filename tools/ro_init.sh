#!/bin/bash

# Based on https://github.com/firecracker-microvm/firecracker-containerd/blob/main/tools/image-builder/files_debootstrap/sbin/overlay-init

# Trigger timestamp in FireCracker log file on the host to get boot time
/opt/tools/fc_log_timestamp

# The directory where the overlay file system will be mounted to
overlay_dir=${overlay_dir:-'/overlay'}
if [ ! -d "$overlay_dir" ]; then
    echo -n "FATAL: "
    echo "Overlay directory $overlay_dir does not exist"
    exit 1
fi
# The directory where the original, read-only root file system will be mounted to
rom_dir=${rom_dir:-'/rom'}
if [ ! -d "$rom_dir" ]; then
    echo -n "FATAL: "
    echo "Directory for mounting the read-only root file system $rom_dir does not exist"
    exit 1
fi

# If we're given an overlay, ensure that it really exists. Panic if not.
if [ -n "$overlay_root" ] &&
       [ "$overlay_root" != ram ] &&
       [ ! -b "/dev/$overlay_root" ]; then
    echo -n "FATAL: "
    echo "Overlay root given as $overlay_root but /dev/$overlay_root does not exist"
    exit 1
else
  overlay_root="ram"
fi

if [ -z "$tempfs_size" ]; then
    # Default size of tempfs
    tempfs_size="128m"
fi

# Parameters:
# 1. rw_root -- path where the read/write root is mounted
# 2. work_dir -- path to the overlay workdir (must be on same filesystem as rw_root)
# Overlay will be set up on /mnt, original root on /mnt/rom
pivot() {
    local rw_root work_dir
    rw_root="$1"
    work_dir="$2"
    /bin/mount \
	-o noatime,lowerdir=/,upperdir=${rw_root},workdir=${work_dir} \
	-t overlay "overlayfs:${rw_root}" /mnt
    pivot_root /mnt /mnt/rom
    # We have to move the devtmpfs file system to the new root
    # because it won't work from the overlay file system.
    mount --move /rom/dev /dev
    # Now that we have a new root, we can also mount the proc file system
    mount -t proc proc /proc
}

# Overlay is configured under /overlay
# Global variable $overlay_root is expected to be set to either:
# "ram", which configures a tmpfs as the rw overlay layer (this is
# the default, if the variable is unset)
# - or -
# A block device name, relative to /dev, in which case it is assumed
# to contain an ext4 filesystem suitable for use as a rw overlay
# layer. e.g. "vdb"
do_overlay() {
    local overlay_dir="/overlay"
    if [ "$overlay_root" = ram ] ||
           [ -z "$overlay_root" ]; then
        /bin/mount -t tmpfs -o noatime,mode=0755,size=$tempfs_size tmpfs /overlay
    else
        /bin/mount -t ext4 "/dev/$overlay_root" /overlay
    fi
    mkdir -p /overlay/root /overlay/work
    pivot /overlay/root /overlay/work
}

do_overlay

# Mount sysfs in case we need more insights int othe kernel
mount -t sysfs sysfs /sys
mount -t debugfs debugfs /sys/kernel/debug/

if [[ -n "$sshd" && ( "$sshd" == "true" || "$sshd" == "on" || "$sshd" == "1" ) ]]; then
  # Start ssh daemon for debugging
  echo "Starting ssh daemon" > /dev/kmsg
  # Also mount the devpts file system such that sshd can assign pseudo terminals (PTYs).
  mkdir -p /dev/pts
  mount -t devpts devpts /dev/pts
  /sbin/sshd
fi

init_script=${init_script:-'/opt/tools/crac_init.sh'}

echo "Successfully overlayed read-only rootfs with /dev/$overlay_root" > /dev/kmsg
echo "Now starting $init_script $@" > /dev/kmsg

exec $init_script $@
