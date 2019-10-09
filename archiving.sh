#!/bin/bash

BASE_PATH=".."
MVN_FLAGS="-DskipTests"
#MVN_FLAGS=""

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NOCOLOUR='\033[0m'


declare -A components
components=(
    [soknadsmottaker]=build_soknadsmottaker
    [soknadsarkiverer]=build_soknadsarkiverer
    [joark-mock]=build_joark-mock
)

build() {
	path="$1"
	command="$2"

	start=$(date +%s)
	cd $BASE_PATH
	cd $path

	mvn clean install $MVN_FLAGS
}
build_soknadsmottaker() {
	build "soknadsmottaker"
}
build_soknadsarkiverer() {
	build "soknadsarkiverer"
}
build_joark-mock() {
	build "joark-mock"
}
build_components_and_show_progress() {
	declare -A jobs

	longestname=0
	for key in "${!components[@]}"; do
		command=${components[$key]}

		$command 1> /dev/null &
		pid=$!
		jobs[$key]=$pid

		namelen=${#key}
		if [[ $namelen -ge $longestname ]]; then
			longestname=$namelen
		fi
	done


	j=1
	sp="/-\|"
	while true; do

		arr=()
		for key in "${!jobs[@]}"; do
			pid=${jobs[$key]}

			if [[ $pid != 0 ]] && [ -d /proc/$pid ]; then
				arr+=($key)
			elif [[ $pid != 0 ]]; then
				jobs[$key]=0

				namelen=${#key}
				spaces=$((longestname-namelen))

				printf "\033[KBuilding $key"
				printf " %.0s" $(seq 0 $spaces)
				printf "... [${GREEN}DONE${NOCOLOUR}]\n"
			fi
		done

		if [[ ${#arr[@]} == 0 ]]; then
			break
		fi


		namesstr=""
		for i in "${arr[@]}"; do
			namesstr="${namesstr}${YELLOW}${i}${NOCOLOUR}, "
		done
		if [[ $namesstr != "" ]]; then
			namesstr=${namesstr::-2}
			spinnerstr="${sp:j++%${#sp}:1}"

			dispstr="Building $namesstr ... $spinnerstr"
			printf "\033[K${dispstr}"$'\r'
		fi

		sleep 1
	done
	printf "\n"

	wait
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
build_components_and_show_progress &
wait
start-docker

echo ""
docker-compose ps
echo ""

echo "Waiting for services to start ..."
wait_for_service_to_start "soknadsmottaker" "8090" &
wait_for_service_to_start "soknadsarkiverer" "8091" &
wait_for_service_to_start "joark-mock" "8092" &
wait

sleep 2
echo "Testing"
curl -s -XPOST -d 'test' http://localhost:8090/save
sleep 2
curl -s -XGET http://localhost:8092/joark/lookup/TSET
