# Android client build

`openmcx-client-v1.apk` is the current debug build of the Android client (`android/`), signed with the build host's
debug key. It is replaced by every rebuild; the version number in the file name changes only for a new release.
Install it with `adb install -r openmcx-client-v1.apk` and activate the device from an MCX Admin activation link or QR
code (see `android/README.md`).
