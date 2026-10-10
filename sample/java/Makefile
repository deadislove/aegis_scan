# AegisScan Java sample -- no Maven/Gradle, matching bindings/java's own build.
#
#   make run                              # build native libs if missing, compile, run
#   make run ARGS="some OCR text"         # run against real camera-recognized text

JAVA_HOME ?= $(shell /usr/libexec/java_home 2>/dev/null)
OUT       := out
BINDINGS  := ../../bindings/java/src/main/java
SRC       := src/main/java
ARGS      :=

.PHONY: run build libs clean

run: build
	java --enable-preview --enable-native-access=ALL-UNNAMED -cp $(OUT) com.aegisscan.sample.Main $(ARGS)

build: libs
	javac --release 21 --enable-preview -d $(OUT) \
	  $$(find $(BINDINGS) -name '*.java') \
	  $$(find $(SRC) -name '*.java')

libs:
	$(MAKE) -C ../../core lib jni

clean:
	rm -rf $(OUT)
