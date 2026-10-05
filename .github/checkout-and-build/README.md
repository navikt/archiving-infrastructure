# Checkout and build
This action checks out the given project code, runs `mvn clean install` on it, and finally does a `docker build`.

This action is used as a composite run step by the Github Actions of the archiving projects.

Several components can be built in parallel by listing them in `component_names`, one per line,
optionally with a `component@branch` suffix to override the branch.

Set `prepull_test_images` to `true` when the build will be followed by the system tests. The action
then pulls the external Docker images used by the tests in the background while the components are
built. The image names and versions are maintained by this action. A failed pre-pull is only a
warning, since Testcontainers pulls any missing image on demand.
