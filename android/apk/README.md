# Android client build

Two builds of the Android client (`android/`), both signed with the build host's debug key, so either installs over the
other with `adb install -r`:

- `openmcx-client-v1.apk`: release 1.0.0, the stable client, as the `1.0.0` images on Docker Hub. It changes only with a
  new release.
- `openmcx-client-develop.apk`: the `main` branch, as the `develop` images on Docker Hub, updated after the regression
  and tutorial tests. It is the `develop` build type: the debug build with its libraries shrunk by R8, about a third of
  the size.

Install one with `adb install -r <file>` and activate the device from an MCX Admin activation link or QR code (see
`android/README.md`).
