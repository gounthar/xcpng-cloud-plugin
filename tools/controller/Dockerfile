# The plugin builds against core 2.555.3 (its jenkins.version); the lab controller runs the latest LTS
# instead, so it is the newest core the plugin is checked on, not the oldest. JDK 25 matches the
# golden image's Temurin 25.
FROM jenkins/jenkins:2.580.1-lts-jdk25

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# The plugin under test is deliberately not copied into the image. Baked into ref/plugins, the
# image's start script compares version strings and can keep a stale build over a newer deploy, or
# replace a deployed one with the baked one. start.sh installs it from a mounted directory instead.
COPY --chmod=755 start.sh /usr/local/bin/xcpng-start.sh
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/xcpng-start.sh"]
