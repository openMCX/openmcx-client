#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# Android build environment: Temurin JDK 17, Android SDK platform 37.0, build-tools 37.0.0 (and 36.0.0, the AGP default).
FROM eclipse-temurin:17-jdk
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -sSL -o /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip -q /tmp/ct.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/ct.zip \
 && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
 && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0" "platform-tools" >/dev/null \
 && chmod -R a+rwX $ANDROID_HOME \
 && mkdir -p /gradle /home/builder && chmod 1777 /gradle /home/builder
