.PHONY: default
.PHONY: prepare prepare-asmdex prepare-soot
.PHONY: development development-build development-package development-install
.PHONY: release release-build release-package release-install
.PHONY: install doc
.PHONY: clean test

.DEFAULT: default

NAME:=tbnl-apk
SRC:=$(shell find src -type f -name '*.clj' -o -name '*.java') project.clj
SRC_MAIN:=src/tbnl_apk/core.clj

# see if Grenchman Leiningen wrapper could be used
LEIN:=$(shell \
    grench lein help > /dev/null 2>&1; \
    if [ $$? = 0 ]; then \
      echo grench lein; \
    else \
      echo lein; \
    fi)

default: development 

all: release test

development: development.touch development-package development-install

development-build: development.touch

development.touch: $(SRC)
	$(LEIN) uberjar && touch $@

development-package: bin/$(NAME).jar bin/$(NAME) bin/$(NAME)-with-jmx bin/$(NAME)-prep-label bin/android.jar | development-build
	tar cvf $(NAME).tar bin

development-install: bin/$(NAME).jar bin/$(NAME) bin/$(NAME)-with-jmx bin/$(NAME)-prep-label bin/android.jar | development-build
	cp $^ ~/bin/
	chmod +x ~/bin/$(NAME)

release: release-build release-package release-install doc

release-build: release.touch

release.touch: $(SRC)
	perl -pli.bak -e 'BEGIN { chomp($$G=`git rev-parse HEAD`); chomp($$T=`date -u +"%a %Y%m%d %T %Z"`); } s/<COMMIT>/$$T ($$G)/;' $(SRC_MAIN); \
	    $(LEIN) uberjar; RET=$$?;\
	    cp -f $(SRC_MAIN).bak $(SRC_MAIN);\
	    if [ $$RET -eq 0 ]; then touch $@; else false; fi; 

release-package: bin/$(NAME).jar bin/$(NAME) bin/$(NAME)-with-jmx bin/$(NAME)-prep-label bin/android.jar | release-build
	tar cvf $(NAME).tar bin

release-install: bin/$(NAME).jar bin/$(NAME) bin/$(NAME)-with-jmx bin/$(NAME)-prep-label bin/android.jar | release-build
	cp $^ ~/bin/
	chmod +x ~/bin/$(NAME)

bin/$(NAME).jar: target/uberjar/$(NAME).jar
	cp $< $@

doc: docs/uberdoc.html

doc/uberdoc.html: $(SRC)
	$(LEIN) marg

test:
	bin/tbnl-apk-prep-label 'Testware' 01sample | tbnl-apk-with-jmx 2014 -svvv

# localrepo cannot use grench wrapper, otherwise directory may be wrong
prepare: prepare-asmdex prepare-soot

prepare-asmdex: 00deps/asmdex.jar
	lein localrepo install $< asmdex/asmdex 1.0

prepare-soot: 00deps/soot-trunk.jar
	lein localrepo install $< soot/soot 1.0

00deps/asmdex.jar: 
	wget $$(cat 00deps/asmdex.url) -O $@

00deps/soot-trunk.jar: 
	wget $$(cat 00deps/soot.url) -O $@

00deps/heros.jar: 
	wget $$(cat 00deps/heros.url) -O $@

bin/android.jar: 
	wget $$(cat 00deps/android-jar.url) -O $@

clean:
	rm -rf target *.touch
