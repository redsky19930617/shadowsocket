import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-23"

name := "shadowsocks"

compileOrder in Compile := CompileOrder.JavaThenScala

javacOptions ++= Seq("-source", "1.6", "-target", "1.6")

scalacOptions += "-target:jvm-1.6"

ndkJavah in Android := List()

ndkBuild in Android := List()

typedResources in Android := false

resolvers += Resolver.jcenterRepo

resolvers += "JRAF" at "http://JRAF.org/static/maven/2"

libraryDependencies ++= Seq(
  "dnsjava" % "dnsjava" % "2.1.7",
  "com.github.kevinsawicki" % "http-request" % "5.6",
  "commons-net" % "commons-net" % "3.3",
  "com.google.zxing" % "android-integration" % "3.1.0"
)

libraryDependencies ++= Seq(
  "com.joanzapata.android" % "android-iconify" % "1.0.9",
  "net.glxn.qrgen" % "android" % "2.0",
  "net.simonvt.menudrawer" % "menudrawer" % "3.0.6",
  "com.google.android.gms" % "play-services-base" % "8.1.0",
  "com.google.android.gms" % "play-services-ads" % "8.1.0",
  "com.google.android.gms" % "play-services-analytics" % "8.1.0",
  "com.android.support" % "support-v4" % "23.0.1"
)

libraryDependencies ++= Seq(
  "com.github.mrengineer13" % "snackbar" % "0.5.0",
  "com.nostra13.universalimageloader" % "universal-image-loader" % "1.8.4",
  "com.j256.ormlite" % "ormlite-core" % "4.47",
  "com.j256.ormlite" % "ormlite-android" % "4.47"
)

proguardOptions in Android ++= Seq("-keep class android.support.v4.app.** { *; }",
          "-keep interface android.support.v4.app.** { *; }",
          "-keep class com.actionbarsherlock.** { *; }",
          "-keep interface com.actionbarsherlock.** { *; }",
          "-keep class org.jraf.android.backport.** { *; }",
          "-keep class com.github.shadowsocks.** { *; }",
          "-keep class * extends com.j256.ormlite.** { *; }",
          "-keep class com.joanzapata.** { *; }",
          "-keepattributes *Annotation*",
          "-dontwarn com.google.android.gms.internal.zzhu",
          "-dontwarn org.xbill.**",
          "-dontwarn com.actionbarsherlock.**")
