@echo off
set ADB=D:\develop\Android\Sdk\platform-tools\adb.exe
%ADB% exec-out run-as com.myreader tar -cf - -C /data/data/com.myreader/files library > d:\develop\code\MyReader\tools\acceptance\after_library.tar
%ADB% exec-out run-as com.myreader tar -cf - -C /data/data/com.myreader/files thumb > d:\develop\code\MyReader\tools\acceptance\after_thumb.tar
echo FILES_DONE
