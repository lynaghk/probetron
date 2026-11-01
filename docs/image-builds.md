# Incremental image builds

Run `bb image` on the supported Debian 13 arm64 build host.
The first build compiles probe-rs and prepares the system filesystem.
Later builds reuse that filesystem when its inputs have not changed.
Every build installs the current Probetron release, refreshes the login banner, generates new SSH keys and a machine ID, creates the disk image, and compresses it under `build/`.

The cache key covers image configuration and layers, dependency pins, provisioning code, the release version, working-tree probe-rs sources, staged binaries, and command-line overrides.
Changes to those inputs rebuild the filesystem automatically.
Changes to application code only replace the application in a clean copy of the cached filesystem.
Cargo independently reuses unchanged compilation outputs when building probe-rs.

Run `bb image --from-scratch` to clear probe-rs compilation outputs and rebuild the filesystem and image.
Verified downloads and the pinned image-generator checkout remain available.
This command rebuilds the pinned dependency versions; update `image/manifest.edn` or the probe-rs submodule to select newer versions.

The working filesystem lives in `/var/tmp/probetron-work`.
The cached snapshot lives beside it in `/var/tmp/probetron-work-base`.
Set `TMPDIR` to move both directories to a local filesystem with enough free space.
The snapshot adds about one system filesystem to the build's disk usage, although filesystems with reflink support can share its data blocks with the working copy.
A missing or incomplete snapshot is rebuilt automatically.

For code-only testing on a running rig, use `bb rig-dev-deploy --host HOST` instead of rebuilding an image.
Run `bb rig-dev-reset --host HOST` or reboot the rig to restore its image code.
