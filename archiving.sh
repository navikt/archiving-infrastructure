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
components+=("soknadsfillager")
components+=("joark-mock")


check_sufficient_java_version() {

	local result
	local java_cmd
	if [[ -n $(type -p java) ]]; then
		java_cmd=java
	elif [[ (-n "$JAVA_HOME") && (-x "$JAVA_HOME/bin/java") ]]; then
		java_cmd="$JAVA_HOME/bin/java"
	fi
	local IFS=$'\n'
	# remove \r for Cygwin
	local lines=$("$java_cmd" -Xms32M -Xmx32M -version 2>&1 | tr '\r' '\n')
	if [[ -z $java_cmd ]]; then
		result=no_java
	else
		for line in $lines; do
			if [[ (-z $result) && ($line = *"version \""*) ]]; then
				local ver=$(echo $line | sed -e 's/.*version "\(.*\)"\(.*\)/\1/; 1q')
				# on macOS, sed doesn't support '?'
				if [[ $ver = "1."* ]]; then
					result=$(echo $ver | sed -e 's/1\.\([0-9]*\)\(.*\)/\1/; 1q')
				else
					result=$(echo $ver | sed -e 's/\([0-9]*\)\(.*\)/\1/; 1q')
				fi
			fi
		done
	fi
	if [[ $result -lt 11 ]]; then
		echo "Needs to have at least version 11 of Java installed. Detected version: $result"
		exit 1
	fi
}

check_if_components_exists() {
	status=0
	for comp in "${components[@]}"; do
		dir="$BASE_PATH/$comp"
		if [ ! -d "${dir}" ] ; then
			echo "Expected to find $comp at $dir  --  Clone with"
			echo "git clone git@github.com:navikt/${comp}.git"
			echo ""
			status=1
		fi
	done
	if [ $status -ne 0 ]; then
		exit 1
	fi
}

check_if_docker_is_running() {
	docker info &> /dev/null
	if [ $? -ne 0 ]; then
		echo "Docker does not seem to be running"
		exit 1
	fi
}

build() {
	path="$1"
	cd "$BASE_PATH/$path"
	mvn clean install $MVN_FLAGS
}
build_components_and_show_progress() {
	status=0
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

			if [[ $pid != 0 ]] && [[ -n $pid ]] && ps -p $pid > /dev/null 2>&1 ; then
				components_being_built+=($comp)
			elif [[ $pid != 0 ]] && [[ -n $pid ]]; then

				wait "$pid"
				jobstatus=$?
				jobs[$index]=0

				namelen=${#comp}
				spaces=$((longestname-namelen))

				printf "\033[KBuilding $comp"
				printf " %.0s" $(seq 0 $spaces)
				if [[ $jobstatus != 0 ]]; then
					status=1
					printf "... [${RED}FAIL${NOCOLOUR}]\n"
				else
					printf "... [${GREEN}DONE${NOCOLOUR}]\n"
				fi
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
	return $status
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
	url="$2"

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

check_if_components_exists
check_sufficient_java_version
check_if_docker_is_running

clean_docker > /dev/null
build_components_and_show_progress
if [ $? -ne 0 ]; then
	echo "Failed to build, exiting."
	exit 1
fi
start-docker

echo ""
docker-compose ps
echo ""

echo "Waiting for services to start ..."
wait_for_service_to_start "soknadsmottaker"  "http://localhost:8090/internal/health" &
wait_for_service_to_start "soknadsarkiverer" "http://localhost:8091/actuator/health" &
wait_for_service_to_start "soknadsfillager"  "http://localhost:9042/internal/health" &
wait_for_service_to_start "joark-mock"       "http://localhost:8092/internal/health" &
wait

cd arkivering-end-to-end-tests
mvn clean install
cd ..
