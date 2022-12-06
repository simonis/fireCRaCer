#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

# Consider using the following settings to achive more stable results:
#
# echo 0 > /proc/sys/vm/compaction_proactiveness
# echo 0 > /proc/sys/vm/compact_unevictable_allowed
# echo 3 > /proc/sys/vm/drop_caches
# echo 1 > /proc/sys/vm/compact_memory

for PID in `ps -e -o pid | tail +2`; do
  # For kernel threads /proc/<pid>/exe is a broken link so stat will return an error
  stat /proc/$PID/exe > /dev/null 2>&1
  # Exclude ourselves (i.e. $$) and our parent process (usually a newly spawned sshd process)
  if [ $? -eq 0 -a $PID -ne $$ -a $PID -ne $PPID ]; then
    $MYPATH/virt2phys $PID;
  fi
done
# Now get the kernel memory..
$MYPATH/virt2phys kernel;
# ..and the page cache
$MYPATH/virt2phys pagecache;
