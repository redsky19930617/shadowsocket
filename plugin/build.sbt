enablePlugins(AndroidLib)
android.useSupportVectors

name := "plugin"
version := "0.0.1-SNAPSHOT"

publishTo := Some(Resolver.file("file", new File(Path.userHome.absolutePath + "/.m2/repository")))
