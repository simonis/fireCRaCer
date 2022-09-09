#!/bin/bash

# Minimal script to start Spring PetClinic

while getopts 's' opt; do
  case "$opt" in
    s)
      # Start ssh daemon for debugging
      echo "crac_init.sh: Starting ssh daemon" > /dev/kmsg
      # Also mount the devpts file system such that sshd can assign pseudo terminals (PTYs).
      mkdir -p /dev/pts
      mount -t devpts devpts /dev/pts
      /sbin/sshd
      ;;
  esac
done
shift "$(($OPTIND -1))"

echo "crac_init.sh: Starting petclinic" > /dev/kmsg
/opt/jdk/bin/java -XX:+IgnoreUnrecognizedVMOptions -Dcom.sun.management.jmxremote.port=5555 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djdk.crac.debug=true -XX:+UnlockDiagnosticVMOptions -XX:+CRPrintResourcesOnCheckpoint -XX:CRaCCheckpointTo=/tmp/crac_x -XX:CREngine=/opt/jdk17-crac/lib/pauseengine -showversion -jar /opt/jars/spring-petclinic*.jar

exit
