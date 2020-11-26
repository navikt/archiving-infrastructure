#!/bin/bash

source build.sh

start-docker() {
	echo ""
	echo ""
	echo "Starting docker ..."
	docker-compose up -d kafka-broker
	sleep 10  # Wait for kafka-broker to finish initialization before starting other containers
	docker-compose up -d
}

wait_for_service_to_start() {
	component="$1"
	url="http://localhost:$2/internal/health"

	for i in {1..90}
	do
		if [[ $(curl -s -XGET $url) == {\"status\":\"UP\"* ]]; then
			echo -e "${GREEN}Started $component${NOCOLOUR}"
			return
		fi
		sleep 1
	done
	echo -e "${RED}FAILED TO START $component${NOCOLOUR}"
}

start-docker

echo ""
docker-compose ps
echo ""

echo "Waiting for services to start ..."
wait_for_service_to_start "soknadsmottaker"  8090 &
wait_for_service_to_start "soknadsarkiverer" 8091 &
wait_for_service_to_start "soknadsfillager"  9042 &
wait_for_service_to_start "arkiv-mock"       8092 &
wait
