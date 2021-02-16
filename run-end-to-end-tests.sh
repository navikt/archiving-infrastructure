#!/bin/bash

if [ "$1" == "no-testcontainers" ]; then
  source start-components-in-docker.sh

  cd system-tests || exit
  mvn -DtargetEnvironment=docker clean install
  cd ..

else
  source build.sh

  cd system-tests || exit
  mvn -DtargetEnvironment=embedded clean install
  cd ..
fi
