#!/bin/bash

adb logcat -d --pid=$(adb shell pidof -s com.landenlabs.all_homewx) 2>/dev/null | tail -300
