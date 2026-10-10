# AegisScan core -- build & test without CMake.
#
#   make test        # Release build + all GoogleTest suites
#   make asan        # AddressSanitizer + UndefinedBehaviorSanitizer
#   make tsan        # ThreadSanitizer (concurrency suites)
#   make lib         # Shared library for Swift / C# / Java (libaegis_core.*)
#   make jni         # JNI fallback library (needs JAVA_HOME)
#
# GoogleTest: set GTEST_DIR to a googletest checkout (default /tmp/aegis_gtest).

CXX       ?= clang++
GTEST_DIR ?= /tmp/aegis_gtest
BUILD     ?= build
UNAME_S   := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
  SHLIB_EXT := dylib
  SHLIB_FLAGS := -dynamiclib -install_name @rpath/libaegis_core.dylib
else
  SHLIB_EXT := so
  SHLIB_FLAGS := -shared -Wl,-soname,libaegis_core.so
endif

WARN      := -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion
CXXFLAGS  := -std=c++20 $(WARN) -Iinclude
GTEST_INC := -isystem $(GTEST_DIR)/googletest/include -I$(GTEST_DIR)/googletest
TEST_SRCS := tests/core_inline_tests.cpp tests/c_abi_tests.cpp src/fintech_core_c.cpp

.PHONY: all test asan tsan lib jni clean
.SECONDARY:

all: lib test

$(BUILD)/gtest-%.a:
	@mkdir -p $(BUILD)
	$(CXX) -std=c++20 $(FLAGS_$*) $(GTEST_INC) -c $(GTEST_DIR)/googletest/src/gtest-all.cc -o $(BUILD)/gtest-all-$*.o
	$(CXX) -std=c++20 $(FLAGS_$*) $(GTEST_INC) -c $(GTEST_DIR)/googletest/src/gtest_main.cc -o $(BUILD)/gtest_main-$*.o
	ar rcs $@ $(BUILD)/gtest-all-$*.o $(BUILD)/gtest_main-$*.o

FLAGS_release := -O2
FLAGS_asan    := -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=all
FLAGS_tsan    := -O1 -g -fsanitize=thread

$(BUILD)/tests-%: $(TEST_SRCS) include/aegisscan/*.h* $(BUILD)/gtest-%.a
	$(CXX) $(CXXFLAGS) $(FLAGS_$*) -DAEGIS_CORE_BUILD $(GTEST_INC) $(TEST_SRCS) $(BUILD)/gtest-$*.a -lpthread -o $@

test: $(BUILD)/tests-release
	./$(BUILD)/tests-release

ifeq ($(UNAME_S),Darwin)
  ASAN_ENV := ASAN_OPTIONS=detect_stack_use_after_return=1  # LSan unsupported.
else
  ASAN_ENV := ASAN_OPTIONS=detect_leaks=1:detect_stack_use_after_return=1
endif

asan: $(BUILD)/tests-asan
	$(ASAN_ENV) ./$(BUILD)/tests-asan

tsan: $(BUILD)/tests-tsan
	TSAN_OPTIONS=halt_on_error=1 ./$(BUILD)/tests-tsan --gtest_filter='*Concurren*:*Concurrent*'

lib: $(BUILD)/libaegis_core.$(SHLIB_EXT)

$(BUILD)/libaegis_core.$(SHLIB_EXT): src/fintech_core_c.cpp include/aegisscan/*.h*
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) -O2 -fPIC -fvisibility=hidden -DAEGIS_CORE_BUILD $(SHLIB_FLAGS) src/fintech_core_c.cpp -o $@

JAVA_HOME ?= $(shell /usr/libexec/java_home 2>/dev/null)
ifeq ($(UNAME_S),Darwin)
  JNI_PLATFORM := darwin
  JNI_LIB := $(BUILD)/libaegis_jni.dylib
else
  JNI_PLATFORM := linux
  JNI_LIB := $(BUILD)/libaegis_jni.so
endif

jni: $(JNI_LIB)

$(JNI_LIB): bindings/jni/aegis_jni.cpp src/fintech_core_c.cpp include/aegisscan/*.h*
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) -O2 -fPIC -fvisibility=hidden -DAEGIS_CORE_BUILD \
	  -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/$(JNI_PLATFORM) \
	  -shared bindings/jni/aegis_jni.cpp src/fintech_core_c.cpp -o $@

clean:
	rm -rf $(BUILD)
