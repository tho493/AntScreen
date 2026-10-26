LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := $(TARGET_ARCH_ABI)
LOCAL_SRC_FILES := antscreen_zygisk.cpp
LOCAL_LDLIBS := -llog
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -O3 -fvisibility=hidden -fvisibility-inlines-hidden
include $(BUILD_SHARED_LIBRARY)
