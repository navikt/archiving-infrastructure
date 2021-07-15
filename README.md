# archiving-infrastructure
This repository contains scripts to run the entire archiving system locally. It also contains end-to-end tests and load tests. For a description of the whole archiving system, see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

As is evident by their names, the end-to-end tests will perform various tests on the system as a whole from the outside and test various behaviours of the system. The load tests will make many simultaneous requests to the system and make sure that the components can handle the load without breaking.

### Repository overview
* **docs**: Documentation resources.
* **system-tests**: Contains the end-to-end tests and load tests.
* **docker-compose.yml**: File with definitions on how to build Docker images locally.
* ***.sh**: Various scripts to automate running the end-to-end tests, building components etc. See [the Scripts section](#Scripts) for more details

## Environments
The end-to-end and load tests can run in various different environments:

### testcontainers (default)
The easiest way to run the end-to-end tests locally is with `./run-end-to-end-tests.sh`, which will build soknadsmottaker, soknadsfillager and soknadsarkiverer, create Docker images of them and pull external ones, and then execute the end-to-end tests. Once the Docker images are present on a machine, the end-to-end tests can instead be run with `mvn clean install` in the system-tests directory. This saves time compared to running the `./end-to-end-tests.sh` script. The downside of using Maven, however, is that the user manually needs to update the Docker images if local changes are made to the code bases of the applications. Therefore, it is easier to simply use the script directly.

The end-to-end tests are in this mode run in testcontainers, which means that the tests themselves will start up Docker containers and run the tests towards them. Apart from having the Docker images available locally, no further setup is needed in this mode. When the tests finish, the Docker containers running the applications are stopped.

### Docker
The end-to-end tests can be run without using testcontainers, and instead running the applications in Docker containers not managed by the tests. To start the tests this way, run `./run-end-to-end-tests.sh no-testcontainers`, which will start all applications and other dependencies in Docker containers and then run the end-to-end tests towards those containers. One can also start all the applications this way, then stop the Docker container for one application (e.g. with `docker-compose stop soknadsarkiverer`) and instead start that application locally in the IDE.

This mode is especially useful for debugging. The downsides are that the user needs to stop the containers manually afterwards. Also, not all of the end-to-end tests will be run; some of them rely on shutting containers down and starting them up (to simulate pods going down), and this can only be done when running with testcontainers, not in this mode.

### GitHub Actions
Upon pull request, GitHub Actions will automatically run the end-to-end tests. It will pull the code for soknadsmottaker, soknadsfillager, soknadsarkiverer and archiving-infrastructure. On the repository that had the pull request, GitHub Actions will use the branch of the pull request, and for the rest of the repositories, it will use the main branch. 

### cronjob
The load tests are run by a cronjob on scheduled times. They will test the upper limits of concurrent load, both with having many simultaneous requests, as well as fewer but heavier. The load tests take almost 1.5 hours to complete, and therefore they are not part of the normal build-chain. Instead, they are run on scheduled times to verify that the system performance has not degraded.

## Scripts

* Run `./run-end-to-end-tests.sh` to build all applications and run the end-to-end tests. This will use testcontainers to start the applications and their dependencies, as described above. By running `./run-end-to-end-tests.sh no-testcontainers`, the applications will start up in "external" Docker containers, and the tests will run against the containers. Running this script is all it takes to run the end-to-end tests; the other scripts will be called by this script.
* Run `./build.sh` to build all applications and create docker images from them.
* Run `./start-components-in-docker.sh` to build and start all applications and their dependencies in Docker.

If you lack permission to execute the scripts, run `chmod +x *.sh`.

## Inquiries
Questions regarding the code or the project can be asked to [team-soknad@nav.no](mailto:team-soknad@nav.no)

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
