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

[![Build Status](https://travis-ci.org/pw4ever/tbnl-apk.svg?branch=gh-pages)](https://travis-ci.org/pw4ever/tbnl-apk?branch=gh-pages)

Take your Android packages (APKs) apart and build a Neo4j database for them.

[The ultimate truth lies in the source](http://pw4ever.github.io/tbnl-apk/docs/uberdoc.html)

## Use

### Dependency

* [Java SE](http://www.oracle.com/technetwork/java/javase/downloads/index.html): `java`
* [Android SDK](https://developer.android.com/sdk/index.html): `aapt` (see [the Travis CI config](https://github.com/pw4ever/tbnl-apk/blob/gh-pages/.travis.yml#L21) for example command-line installation procedure).
* [Neo4j 2.1+](http://neo4j.com/): Neo4j 2.1+ (not an earlier version) is needed due to the Cypher query language used in the implementation.

The shell script `tbnl-apk` and `tbnl-apk-with-jmx` are self-bootstrapping; they require [`bash`](https://www.gnu.org/software/bash/) and assume a Linux/Unix shell environment.

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

Suppose Neo4j server listens on TCP port 7475.

```sh
find 01sample -type f | \
        tbnl-apk --prep-tags '{"Dataset" "My Sample Dataset"}' | \
        JVM_OPTS='-Xmx4g -Xms4g -XX:NewSize=3g' \
        tbnl-apk-with-jmx 2014 -dsntvv --neo4j-port 7475 --nrepl-port 12321 --interactive
```

This will process all `*.apk` (recursively) under the `01sample` directory.
* [Start the JVM with the options](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/java.html#BGBCIEFC) `-Xmx4g -Xms4g -XX:NewSize=3g`. 
  - Allocate enough memory for JVM heap with eager commit, e.g., `-Xmx4g -Xms4g`.
  - Give most memory to the young generation with just enough left for the old generation, e.g., `-XX:NewSize=3g`.
* First decompile and dump its `AndroidManifest.xml` (`-d`).
* Build its graphical model using [Soot]() (`-s`).
* Send the graphical model to Neo4j at port 7475 (`--neo4j-port 7475`).
* Tag the model in Neo4j (`-t`).
* Be doubly verbose (`-vv`).
* Start Clojure nREPL at port 12321: `--nrepl-port 12321`.
* Enter interactive mode (`--interactive`): The program will not shutdown after the above processing is done. This allow you to interact with it continously with nREPL.

### Basics

Get help.

```sh
tbnl-apk -h
```

* Inputs come from standard input (`stdin`). Each line corresponds to one input APK sample, and is in [Clojure edn format](https://github.com/edn-format/edn). Say you have an APK file in the path `01sample/test.apk`, and you want to attach tags with types (types must be [valid Neo4j Cypher identifier names](http://neo4j.com/docs/stable/cypher-identifiers.html)) "Dataset" and "Source" with identifiers "My Dataset" and "Internet" respectively, the input line should be: `{:file-path "01sample/test.apk" :tags {"Dataset" "My Dataset" "Source" "Internet"}}`.
In the final Neo4j database after applying these tags, you can find Neo4j nodes with labels of `:Tag:Dataset` and `:Tag:Source` that point to the `:Apk` node representing the APK sample. You can use `tbnl-apk --prep-tags` to ease the tag preparation task: See the [Quick Test](#quick-test) example above.

* To start the program with [JVM JMX](http://docs.oracle.com/javase/8/docs/technotes/guides/visualvm/jmx_connections.html) on port 2014 (so that you can [point VisualVM to this port for dynamically monitoring the JVM hosting `tbnl-apk.jar`](http://theholyjava.wordpress.com/2012/09/21/visualvm-monitoring-remote-jvm-over-ssh-jmx-or-not/) and [Clojure nREPL port](https://github.com/clojure/tools.nrepl) 12321 (so you can dynamically interact with application in [Clojure REPL](https://www.youtube.com/watch?v=fnn8JeKfzWY)), the `--interactive` argument instructs `tbnl-apk` to enter "interactive" mode, i.e., do not quit at then end, to allow nREPL to be connected. You can tune the JVM with the `JVM_OPTS` environment variable.

```sh
JVM_OPTS='-Xmx4g -Xms4g -XX:NewSize=3g' \
         tbnl-apk-with-jmx 2014 --nrepl-port 12321 --interactive
```

If the first parameter of is not a valid TCP port number, `tbnl-apk-with-jmx` will fall back to `tbnl-apk`.

* (Or) With `java` and `tbnl-apk.jar` (make sure the `android.jar` is at the same directory as `tbnl-apk.jar`, or prepare to specify its path with <argument>)
```sh
java -jar tbnl-apk.jar \
         -Xmx4g -Xms4g -XX:NewSize=3g \
         tbnl-apk.core <argument> 
```

Take input APK file names line-by-line as a [Unix filter](https://en.wikipedia.org/wiki/Filter_(software)#Unix) (e.g., use `find dir -name '*.apk' -type f` to find APKs to feed into `tbnl-apk`).

Again, use `-h` for valid arguments.

More to come on [project Wiki](https://github.com/pw4ever/tbnl-apk/wiki)


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
