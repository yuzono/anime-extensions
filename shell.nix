{
  pkgs ?
    import <nixpkgs> {
      config = {
        allowUnfree = true;
        android_sdk.accept_license = true;
      };
    },
}: let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    buildToolsVersions = ["36.0.0"];
    platformVersions = ["34"];
    abiVersions = ["armeabi-v7a"];
  };
in
  pkgs.mkShell {
    buildInputs = with pkgs; [
      gradle_9
      jdk17
      androidComposition.androidsdk
    ];

    JAVA_HOME = pkgs.jdk17.home;
    ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
    ANDROID_SDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk";

    GRADLE_OPTS = pkgs.lib.concatStringsSep " " [
      "-Dorg.gradle.java.installations.auto-download=false"
      "-Dorg.gradle.project.android.aapt2FromMavenOverride=${pkgs.aapt}/bin/aapt2"
    ];
  }
