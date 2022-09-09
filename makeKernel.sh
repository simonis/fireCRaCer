#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

DOCKER_IMG="kernel.test"
DOCKER_FILE="$MYPATH/Dockerfile.kernel"
KERNEL_URL="https://cdn.kernel.org/pub/linux/kernel/v5.x/linux-5.19.8.tar.xz"
CONFIG_URL="https://raw.githubusercontent.com/firecracker-microvm/firecracker/main/resources/guest_configs/microvm-kernel-x86_64-5.10.config"

while getopts 'k:c:h' opt; do
  case "$opt" in
    d)
      KERNEL_URL="$OPTARG"
      ;;
    i)
      CONFIG_URL="$OPTARG"
      ;;
    ?|h)
      echo "Usage: $(basename $0) [-k <kernel.url>] [-c <config-url>]"
      echo "  -k <kernel-url>: e.g. https://cdn.kernel.org/pub/linux/kernel/v5.x/linux-5.10.139.tar.xz"
      echo "  -c <config-url>: e.g. https://raw.githubusercontent.com/firecracker-microvm/firecracker/main/resources/guest_configs/microvm-kernel-x86_64-5.10.config"
      exit 1
      ;;
  esac
done
shift "$(($OPTIND -1))"

docker build -t $DOCKER_IMG -f $DOCKER_FILE --build-arg KERNEL=$KERNEL_URL --build-arg KERNEL_CONFIG=$CONFIG_URL .
docker_id=$(docker run -it --rm --detach $DOCKER_IMG)
docker cp $docker_id:/opt/vmlinux $MYPATH/deps
docker kill $docker_id
