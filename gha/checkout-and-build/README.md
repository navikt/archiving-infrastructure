# Checkout and build
This action checks out the given project code, runs `mvn clean install` on it, and finally does a `docker build`.

This action is used as a composite run step by the Github Actions of the archiving projects.
