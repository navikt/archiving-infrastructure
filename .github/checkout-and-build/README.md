# Checkout and build
This action checks out the given project code, runs `mvn clean install` on it, and finally does a `docker build`.

This action is used as a composite run step by the Github Actions of the archiving projects.

Several components can be built in parallel by listing them in `component_names`, one per line,
optionally with a `component@branch` suffix to override the branch.

External Docker images listed in `prepull_images` are pulled in the background while the components
are built, so that the pull time does not land in the test phase. A failed pre-pull is only a
warning, since Testcontainers pulls any missing image on demand.
