#!/bin/bash

# Minimal script to start Spring PetClinic
# We use -Dlogging.level.org.apache.catalina.authenticator.AuthenticatorBase=DEBUG
# as a simple way to get notifications for each HTTP request.
# Use FC_JAVA_OPTIONS to pass java command line options from host to guest via kernel
# boot parameters (we can't always use _JAVA_OPTIONS here because of JDK-8256844).

# Define CONSOLE_LOG_PATTERN to change the log format. The default value is:
#
# "%clr(%d{${LOG_DATEFORMAT_PATTERN:-yyyy-MM-dd HH:mm:ss.SSS}}){faint} %clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}"
#
# It is defined in: https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/logging/logback/DefaultLogbackConfiguration.java
# The log format is described here: https://logback.qos.ch/manual/layouts.html#ClassicPatternLayout
#

# export CONSOLE_LOG_PATTERN="%clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}"
export CONSOLE_LOG_PATTERN="%clr(%d{hh:mm:ss.SSS}){faint} %clr(%.-1p) %.-65m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}"

# Disable the bash builtin 'kill'
enable -n kill

echo "crac_init.sh: Starting petclinic" > /dev/kmsg

echo_and_exec() { echo "  $@" > /dev/kmsg; "$@" ; }

FC_JAVA_CMD=${FC_JAVA_CMD:-"/opt/jars/spring-petclinic*exec.jar"}

if [[ $FC_JAVA_CMD == *.jar ]]; then
  FC_JAVA_CMD="-jar $FC_JAVA_CMD"
fi

echo_and_exec /opt/jdk/bin/java -XX:+IgnoreUnrecognizedVMOptions -Dcom.sun.management.jmxremote.port=5555 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -showversion -Dlogging.level.org.springframework.web.filter.CommonsRequestLoggingFilter=DEBUG $FC_JAVA_OPTIONS $FC_JAVA_CMD $FC_JAVA_ARGS

echo "Exiting.." > /dev/kmsg
# Gracefully shutdown instead of exiting the init process to avoid a kernel panic on exit
# See: https://en.wikipedia.org/wiki/Magic_SysRq_key
echo b > /proc/sysrq-trigger

sleep 3
exit
