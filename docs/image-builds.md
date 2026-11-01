# Incremental image builds

Run `bb image` on the supported Debian 13 arm64 build host.
The first build compiles probe-rs, prepares the system filesystem, and creates a raw disk image.
Later builds copy the cached disk image and update Probetron inside its existing root partition.
These builds skip filesystem creation, image assembly, and the generator's extra deployment artifacts.
Every build installs the current Probetron release, refreshes the login banner, generates new SSH keys and a machine ID, and compresses the image under `build/`.
The command prints elapsed times for input staging, image preparation, and compression with checksumming.

The cache key covers image configuration and layers, dependency pins, provisioning code, the release version, working-tree probe-rs sources, staged binaries, and command-line overrides.
Changes to those inputs rebuild the filesystem automatically.
Changes to application code only replace the application in a clean copy of the cached disk image.
Cargo independently reuses unchanged compilation outputs when building probe-rs.

Run `bb image --from-scratch` to clear probe-rs compilation outputs and rebuild the filesystem and image.
Verified downloads and the pinned image-generator checkout remain available.
This command rebuilds the pinned dependency versions; update `image/manifest.edn` or the probe-rs submodule to select newer versions.

The build workspace lives in `/var/tmp/probetron-work`.
The cached filesystem and raw disk image live beside it in `/var/tmp/probetron-work-base`.
Set `TMPDIR` to move both directories to a local filesystem with enough free space.
Filesystems with reflink support can share cached data blocks with the working copy.
A missing or incomplete cache is rebuilt automatically.

Cached image updates use `sfdisk`, `losetup`, `mount`, and `unshare` from the build host's util-linux tools.
The root partition is mounted read-write in a private mount namespace and unmounted before compression.
The cached image remains unchanged.

Images use XZ level 1 to favor build speed.
A measured image compressed in about 4.5 seconds at 238 MiB, compared with 24 seconds at 197 MiB with level 6.
Image contents and the flashing command remain unchanged.

For code-only testing on a running rig, use `bb rig-dev-deploy --host HOST` instead of rebuilding an image.
Run `bb rig-dev-reset --host HOST` or reboot the rig to restore its image code.
