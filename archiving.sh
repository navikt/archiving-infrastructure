#!/bin/bash

BASE_PATH=".."
MVN_FLAGS="-DskipTests"
#MVN_FLAGS=""

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NOCOLOUR='\033[0m'


components=()
components+=("soknadsmottaker")
components+=("soknadsarkiverer")
components+=("joark-mock")

check_if_docker_is_running() {
	docker info &> /dev/null
	if [ $? -ne 0 ]; then
		echo "Docker does not seem to be running"
		exit 1
	fi
}

build() {
	path="$1"

	cd $BASE_PATH
	cd $path

	mvn clean install $MVN_FLAGS
}
build_components_and_show_progress() {
	jobs=()

	longestname=0
	for comp in "${components[@]}"; do

		build ${comp} 1> /dev/null &
		pid=$!
		jobs+=($comp)
		jobs+=($pid)

		namelen=${#comp}
		if [[ $namelen -ge $longestname ]]; then
			longestname=$namelen
		fi
	done


	j=1
	spinner="/-\|"
	while true; do

		components_being_built=()
		index=0
		while [ $index -le ${#jobs[@]} ]; do

			comp=${jobs[$index]}
			index=$((index + 1))
			pid=${jobs[$index]}

			if [[ $pid != 0 ]] && [ -d /proc/$pid ]; then
				components_being_built+=($comp)
			elif [[ $pid != 0 ]]; then
				jobs[$index]=0

				namelen=${#comp}
				spaces=$((longestname-namelen))

				printf "\033[KBuilding $comp"
				printf " %.0s" $(seq 0 $spaces)
				printf "... [${GREEN}DONE${NOCOLOUR}]\n"
			fi
			index=$((index + 1))
		done

		if [[ ${#components_being_built[@]} == 0 ]]; then
			break
		fi


		namesstr=""
		for i in "${components_being_built[@]}"; do
			namesstr="${namesstr}${YELLOW}${i}${NOCOLOUR}, "
		done
		if [[ $namesstr != "" ]]; then
			namesstr=${namesstr::-2}
			spinnerstr="${spinner:j++%${#spinner}:1}"

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

check_if_docker_is_running
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

cd arkivering-end-to-end-tests
mvn clean install
cd ..
