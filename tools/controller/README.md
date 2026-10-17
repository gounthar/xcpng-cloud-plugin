# Lab controller image

The Jenkins controller the lab measurements run against: a Docker Compose service on the VM that
`tools/build_controller.py` creates. That script stops at a VM with Docker installed; this directory
is what runs on it.

| File | Role |
|---|---|
| `Dockerfile` | Jenkins LTS plus the plugins in `plugins.txt`. Does **not** contain the plugin under test. |
| `start.sh` | Entrypoint. Copies the plugin under test into `JENKINS_HOME`, then runs the image's own start script. |
| `compose.yaml` | The service, the `JENKINS_HOME` volume, and two read-only directory mounts. |
| `casc/casc.yaml` | Configuration as Code. Every site detail and secret comes from `.env`. |
| `.env.example` | The variables `compose.yaml` refuses to start without. |

## Bring-up

On the controller VM:

```bash
cp .env.example .env              # then fill it in
mkdir -p plugin
cp /path/to/xcpng-cloud.hpi plugin/
docker compose up -d --build
```

## Deploying a plugin build

```bash
cp target/xcpng-cloud.hpi plugin/xcpng-cloud.hpi
docker restart jenkins
```

No image rebuild. `start.sh` copies whatever `plugin/xcpng-cloud.hpi` is on every start.

## Checking which build is running

The version string is what the archive claims about itself. To know which build is actually
running, compare the jar Jenkins exploded with the jar inside the file you deployed:

```bash
python3 -c 'import zipfile, hashlib; print(hashlib.md5(zipfile.ZipFile("plugin/xcpng-cloud.hpi").read("WEB-INF/lib/xcpng-cloud.jar")).hexdigest())'
docker exec jenkins md5sum /var/jenkins_home/plugins/xcpng-cloud/WEB-INF/lib/xcpng-cloud.jar
```

The first line uses Python rather than `unzip`, which a Debian cloud image does not install.

Do not use `copy_reference_file.log` for this. With a reference-copy override it can print
`SKIPPED ... Image version (1.5.2) is older than installed version (1.5.2-273)` while copying the
file anyway.

## Why the plugin is not baked into the image

An image that copies the plugin into `/usr/share/jenkins/ref/plugins` hands the decision to the
image's reference copy. On each start that copy compares the baked version string with the one
already in `JENKINS_HOME`. A build put in place with `docker cp` is then replaced by the baked
one, or kept, depending on how two version strings compare, not on which build you meant to run.
In #156 this meant four lab runs measured a build from the day before.

`start.sh` copies without any version check. Two builds of the same version and a downgrade both
deploy the same way.

## Why directories are mounted, not files

Docker binds a single file by inode. `sed -i`, most editors and `mv` replace a file with a new
inode, and the container keeps reading the old one. For `casc.yaml` that means a JCasC reload
picks up a stale config. For a plugin file, Docker Desktop then refuses to restart the container at
all. A directory mount sees every replacement, so `casc/` and `plugin/` are mounted as directories.

## Verified

Locally with Docker 29.8.2 on 2026-10-16, against `jenkins/jenkins:2.580.1-lts-jdk25`, using a
fresh volume and two builds whose jars differ (`819d72f1` and `85d2ebbc`):

1. Fresh bring-up: the cloud is created from `casc.yaml`, and the running jar is the deployed one.
2. In-place overwrite with the older release build, then a restart: the release jar runs.
3. Replacement by `mv` (a new inode), then a restart: the newer build runs again.
4. `sed -i` on `casc/casc.yaml`: the container sees the change at once.
5. No `plugin/xcpng-cloud.hpi`: `start.sh` exits with an error naming the missing path, and the
   container restarts in a loop on that error rather than bringing up a controller with no cloud.

Not yet run on the lab controller itself.
