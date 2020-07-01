# archiving-infrastructure
This repository contains docker files and scripts to run the entire archiving system locally. It also contains end-to-end tests. For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

* Run `./build.sh` to build all applications and create docker images from them.
* Run `./start-components-in-docker.sh` to build and start all applications and their dependencies in Docker.
* Run `./run-end-to-end-tests.sh` to build all applications and run the end-to-end tests. This will use testcontainers to start the applications and their dependencies, i.e. the containers are "embedded" in the tests. By running `./run-end-to-end-tests.sh no-testcontainers`, the applications will start up in "external" Docker containers, and the tests will run against the containers.

If you lack permission to execute the scripts, run `chmod +x SCRIPT.sh`.

## Inquiries
Questions regarding the code or the project can be asked to [team-soknad@nav.no](mailto:team-soknad@nav.no)

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
