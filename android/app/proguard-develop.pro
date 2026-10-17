#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# The develop APK (build type develop): the libraries are shrunk, OpenMCX's own classes are kept whole, and no name is
# obfuscated, so that stack traces stay readable.
-keep class org.openmcx.** { *; }
-dontobfuscate
