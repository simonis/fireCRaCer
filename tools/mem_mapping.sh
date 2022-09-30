#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

for PID in `ps -e -o pid`; do
  # For kernel threads /proc/<pid>/exe is a broken link so stat will return an error
  stat /proc/$PID/exe > /dev/null 2>&1
  if [ $? -eq 0 ]; then
    $MYPATH/virt2phys $PID;
  fi
done
