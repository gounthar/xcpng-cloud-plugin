#!/bin/sh
# Install the plugin under test into JENKINS_HOME on every start, then hand over to the image's own
# start script.
#
# The build is copied here rather than baked into the image or left to the reference copy, because
# both of those decide whether to copy by comparing version strings. Two builds of the same commit,
# or an older build replacing a newer one, compare as "no change" and the old binary stays loaded.
# A plain copy on every start has no such opinion: whatever sits in the mounted directory is what
# runs. Not preserving the timestamp also forces Jenkins to re-explode the archive each time.
set -eu

src=/opt/xcpng-cloud/xcpng-cloud.hpi
if [ ! -f "$src" ]; then
    echo "start.sh: $src not found; mount the directory holding the plugin build (see compose.yaml)" >&2
    exit 1
fi
mkdir -p "$JENKINS_HOME/plugins"
cp "$src" "$JENKINS_HOME/plugins/xcpng-cloud.jpi"
# A version marker left by an image that used to bake the plugin would make the reference copy
# reconsider this file; with nothing in ref/ it is inert, but remove it so it cannot mislead anyone.
rm -f "$JENKINS_HOME/plugins/xcpng-cloud.jpi.version_from_image"

exec /usr/local/bin/jenkins.sh "$@"
