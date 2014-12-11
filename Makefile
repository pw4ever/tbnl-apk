.PHONY: all prepare clean

all:

prepare: prepare-asmdex
prepare-asmdex: 00deps/asmdex-1.0.jar
	lein localrepo install $$(lein localrepo coords $<)
