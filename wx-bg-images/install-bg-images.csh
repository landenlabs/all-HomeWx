
 #!/bin/csh -f

 adb shell ls    /sdcard/Documents
 adb shell mkdir /sdcard/Documents/all-HomeWx
 adb push *.jpg  /sdcard/Documents/all-HomeWx
 