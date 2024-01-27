#!/bin/bash

MYPATH=$(dirname $(realpath -s $0))

DOCKER_IMG="kernel.test"
DOCKER_FILE="$MYPATH/Dockerfile.kernel"
KERNEL_URL="https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.1.74.tar.xz"
CONFIG_URL="https://raw.githubusercontent.com/firecracker-microvm/firecracker/main/resources/guest_configs/microvm-kernel-ci-x86_64-6.1.config"
PATCH_FILE="docker/config.damon.6.1.patch"

while getopts 'k:c:p:h' opt; do
  case "$opt" in
    k)
      KERNEL_URL="$OPTARG"
      ;;
    c)
      CONFIG_URL="$OPTARG"
      ;;
    p)
      PATCH_FILE="$OPTARG"
      ;;
    ?|h)
      echo "Usage: $(basename $0) [-k <kernel.url>] [-c <config-url>] [-p <patch-file>]"
      echo "  -k <kernel-url>: e.g. https://cdn.kernel.org/pub/linux/kernel/v5.x/linux-5.10.139.tar.xz"
      echo "  -c <config-url>: e.g. https://raw.githubusercontent.com/firecracker-microvm/firecracker/main/resources/guest_configs/microvm-kernel-x86_64-5.10.config"
      echo "  -p <patch-file>: e.g. docker/config.damon.patch"
      exit 1
      ;;
  esac
done
shift "$(($OPTIND -1))"

docker build -t $DOCKER_IMG -f $DOCKER_FILE --build-arg KERNEL=$KERNEL_URL --build-arg KERNEL_CONFIG=$CONFIG_URL --build-arg PATCH_FILE=$PATCH_FILE .
docker_id=$(docker run -it --rm --detach $DOCKER_IMG)
# If deps/vmlinux is a symbolik link remove it first so we don't overwrite the link target
if [[ -L $MYPATH/deps/vmlinux ]]; then
   rm $MYPATH/deps/vmlinux
fi
docker cp $docker_id:/opt/vmlinux $MYPATH/deps/vmlinux
docker kill $docker_id
