# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

POLIPO_SOURCES := util.c event.c io.c chunk.c atom.c object.c log.c diskcache.c main.c \
	config.c local.c http.c client.c server.c auth.c tunnel.c \
	http_parse.c parse_time.c dns.c forbidden.c \
	md5.c fts_compat.c socks.c mingw.c

LOCAL_MODULE := polipo
LOCAL_SRC_FILES := $(addprefix polipo/, $(POLIPO_SOURCES))
LOCAL_CFLAGS := -O2 -g -DHAS_STDINT_H -DNO_DISK_CACHE -DNO_SYSLOG -I$(LOCAL_PATH)/polipo

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_MODULE := libev
LOCAL_CFLAGS += -O2 -DNDEBUG -DHAVE_CONFIG_H
LOCAL_SRC_FILES := \
	libev/ev.c \
	libev/event.c 

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

PDNSD_SOURCES  := $(wildcard $(LOCAL_PATH)/pdnsd/*.c)

LOCAL_MODULE    := pdnsd
LOCAL_SRC_FILES := $(PDNSD_SOURCES:$(LOCAL_PATH)%=%)
LOCAL_CFLAGS    := -Wall -O2 -I$(LOCAL_PATH)/pdnsd

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_MODULE    := shadowsocks 
LOCAL_SRC_FILES := shadowsocks/local.c shadowsocks/encrypt.c
LOCAL_CFLAGS    := -Wall -O2 -fno-strict-aliasing -I$(LOCAL_PATH)/libev/ -I$(LOCAL_PATH)/openssl/include

LOCAL_STATIC_LIBRARIES := libev libcrypto

LOCAL_LDLIBS := -llog

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_MODULE:= exec

LOCAL_SRC_FILES:= \
	   termExec.cpp

LOCAL_LDLIBS := -ldl -llog

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE:= system

LOCAL_SRC_FILES:= \
	   system.cpp

LOCAL_LDLIBS := -ldl -llog

include $(BUILD_SHARED_LIBRARY)

subdirs := $(addprefix $(LOCAL_PATH)/openssl/,$(addsuffix /Android.mk, \
	crypto \
	))

include $(subdirs)

