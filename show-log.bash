#!/bin/bash

adb logcat -d --pid=$(adb shell pidof -s com.dlang.homewx) 2>/dev/null | tail -300
