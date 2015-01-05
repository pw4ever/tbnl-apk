<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](http://doctoc.herokuapp.com/)*

- [tbnl-apk](#tbnl-apk)
  - [Use](#use)
    - [Dependency](#dependency)
    - [Bootstrapping](#bootstrapping)
    - [Quick Test](#quick-test)
    - [Basics](#basics)
  - [Build](#build)
    - [Dependency](#dependency-1)
    - [Instruction](#instruction)
  - [License](#license)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# tbnl-apk 

[![Build Status](https://travis-ci.org/pw4ever/tbnl-apk.svg?branch=travis)](https://travis-ci.org/pw4ever/tbnl-apk?branch=travis)

Take your Android packages (APKs) apart and build a Neo4j database for them.

[The ultimate truth lies in the source](http://pw4ever.github.io/tbnl-apk/docs/uberdoc.html)

## Use

### Dependency

* [Java SE](http://www.oracle.com/technetwork/java/javase/downloads/index.html): `java`
* [Android SDK](https://developer.android.com/sdk/index.html): `aapt`
* [Neo4j 2.1+](http://neo4j.com/)

The shell script `tbnl-apk`, `tbnl-apk-with-jmx`, and `tbnl-apk-prep-label` are self-bootstrapping, but requires [`bash`](https://www.gnu.org/software/bash/) and assumes a Linux/Unix shell environment.

`tbnl-apk.jar` (produced by the build process) and `android.jar` are [Java jars](https://en.wikipedia.org/wiki/JAR_%28file_format%29), and hence are cross-platform. But they are launched through the `java` command-line launcher.

### Bootstrapping

On `bash` with `wget` and `java` installed (if you have the file `$HOME/bin/tbnl-apk-with-jmx`, remove it first):

```sh
( TARGET="$HOME/bin/";
  mkdir -p ${TARGET};
  wget -nc -nd -P ${TARGET} \
    https://raw.githubusercontent.com/pw4ever/tbnl-apk/gh-pages/bin/tbnl-apk-with-jmx \
  && \
  chmod +x \
    ${TARGET}/tbnl-apk-with-jmx \
  && \
  ${TARGET}/tbnl-apk-with-jmx -h )
```

It is recommended that `$HOME/bin/` being added to your `PATH` environment variable for easy use of `tbnl-apk` and `tbnl-apk-with-jmx` wrapper scripts.

*Alternatively*, download the "ingredients" explicitly with `wget` (existing files will *not* be overwritten due to `wget`'s `-nc`; remove the existing files if you want to get the latest version):

```sh
( TARGET="$HOME/bin/";
  mkdir -p ${TARGET};
  wget -nc -nd -P ${TARGET} \
    https://raw.githubusercontent.com/pw4ever/tbnl-apk/gh-pages/bin/tbnl-apk \
    https://raw.githubusercontent.com/pw4ever/tbnl-apk/gh-pages/bin/tbnl-apk-with-jmx \
    https://github.com/pw4ever/tbnl-apk/releases/download/tryout/android.jar \
    https://github.com/pw4ever/tbnl-apk/releases/download/tryout/tbnl-apk.jar \
  && \
  chmod +x \
    ${TARGET}/tbnl-apk \
    ${TARGET}/tbnl-apk-with-jmx \
  && \
  ${TARGET}/tbnl-apk-with-jmx -h )
```

### Quick Test

```sh
tbnl-apk-prep-label 'Testlabel' 01sample | tbnl-apk-with-jmx 2014 -dsvvv
```

### Basics

* Get help.

```sh
tbnl-apk -h
```

* To start the program with [JVM JMX](http://docs.oracle.com/javase/8/docs/technotes/guides/visualvm/jmx_connections.html) on port 2014 (so that you can [point VisualVM to this port for dynamically monitoring the JVM hosting `tbnl-apk.jar`](http://theholyjava.wordpress.com/2012/09/21/visualvm-monitoring-remote-jvm-over-ssh-jmx-or-not/) and [Clojure nREPL port](https://github.com/clojure/tools.nrepl) 12321 (so you can dynamically interact with application in [Clojure REPL](https://www.youtube.com/watch?v=fnn8JeKfzWY)), the `-i` argument instructs `tbnl-apk` to enter "interactive" mode, i.e., do not quit at then end, to allow nREPL to be connected.

```sh
tbnl-apk-with-jmx 2014 -p 12321 -i
```

If the first parameter is not a valid TCP port number, `tbnl-apk-with-jmx` will fall back to `tbnl-apk`.

* (Or) With `java` and `tbnl-apk.jar` (make sure the dummy `android.jar` is at the same directory as `tbnl-apk.jar`, or prepare to specify its path with <argument>)
```sh
java -jar tbnl-apk.jar -Xmx2048m tbnl-apk.core <argument> 
```

Take input APK file names line-by-line as a [Unix filter](https://en.wikipedia.org/wiki/Filter_(software)#Unix) (e.g., use `find dir -name '*.apk' -type f` to find APKs to feed into `tbnl-apk`).

Use `-h` for valid arguments.

More (exciting) usage to come on [project Wiki](https://github.com/pw4ever/tbnl-apk/wiki)


## Build

### Dependency

* [Leiningen](http://leiningen.org/): [Clojure](http://clojure.org/) and all other [build dependencies](https://github.com/pw4ever/tbnl-apk/blob/github/project.clj) will be bootstrapped by Leiningen.
* [Java SDK](http://www.oracle.com/technetwork/java/javase/downloads/): execute Leiningen.
* [GNU Make](https://www.gnu.org/software/make/): drive the build process.
* [Perl](http://www.perl.org/): (optional) replace version string.
* Internet access to download other dependencies on demand.

### Instruction

```sh
# prepare dependency
make prepare

# use "make development" or simply "make" when developing
make development

# after "git commit", use "make release" to update revision string in release
make release
```

See [Makefile](https://github.com/pw4ever/tbnl-apk/blob/gh-pages/Makefile) for detail.

## License

Copyright © 2014 Wei "pw" Peng (4pengw@gmail.com)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
