#!/bin/bash

# Minimal script to start Spring PetClinic
# We use -Dlogging.level.org.apache.catalina.authenticator.AuthenticatorBase=DEBUG
# as a simple way to get notifications for each HTTP request.
# Use FC_JAVA_OPTIONS to pass java command line options from host to guest via kernel
# boot parameters (we can't always use _JAVA_OPTIONS here because of JDK-8256844).

echo "crac_init.sh: Starting petclinic" > /dev/kmsg

echo_and_exec() { echo "  $@" > /dev/kmsg; "$@" ; }

echo_and_exec /opt/jdk/bin/java -XX:+IgnoreUnrecognizedVMOptions -Dcom.sun.management.jmxremote.port=5555 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -showversion -Dlogging.level.org.apache.catalina.authenticator.AuthenticatorBase=DEBUG $FC_JAVA_OPTIONS -jar /opt/jars/spring-petclinic*.jar

exit
