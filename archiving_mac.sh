#!/usr/bin/env bash

BASE_PATH=".."
MVN_FLAGS="-DskipTests"
#MVN_FLAGS=""

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NOCOLOUR='\033[0m'



build() {
	path="$1"
	command="$2"

	start=$(date +%s)
	cd $BASE_PATH
	cd $path

	mvn clean install $MVN_FLAGS
}
build_producer() {
	build "archiver-producer"
}
build_consumer() {
	build "joark-archiver"
}
build_joark-mock() {
	build "joark-mock"
}

clean_docker() {
	if [[ $(docker ps -qa) ]]; then
		docker stop $(docker ps -qa)
		docker rm $(docker ps -qa)
	fi
	if [[ $(docker volume ls -qf dangling=true) ]]; then
		docker volume rm $(docker volume ls -qf dangling=true)
	fi
}

start-docker() {
	echo "Building docker ..."
	docker-compose build
	echo ""
	echo "Starting docker ..."
	docker-compose up -d
}

wait_for_service_to_start() {
	component="$1"
	port="$2"
	url="http://localhost:${port}/actuator/health"

	for i in {1..90}
	do
		if [[ $(curl -s -XGET $url) == "{\"status\":\"UP\"}" ]]; then
			echo -e "${GREEN}Started $component${NOCOLOUR}"
			return
		fi
		sleep 1
	done
	echo -e "${RED}FAILED TO START $component${NOCOLOUR}"
}

clean_docker > /dev/null &
build_producer &
build_consumer &
build_joark-mock &
wait
start-docker

echo ""
docker-compose ps
echo ""

echo "Waiting for services to start ..."
wait_for_service_to_start "archiver-producer" "8090" &
wait_for_service_to_start "joark-archiver" "8091" &
wait_for_service_to_start "joark-mock" "8092" &
wait

sleep 2
echo "Testing"
curl -s -XPOST -d 'omgwtf' http://localhost:8090/save
sleep 2
curl -s -XGET http://localhost:8092/joark/lookup/FTWGMO
