@echo off
set ADB=D:\develop\Android\Sdk\platform-tools\adb.exe
%ADB% exec-out run-as com.myreader cat /data/data/com.myreader/databases/myreader.db > d:\develop\code\MyReader\tools\acceptance\after\myreader.db
%ADB% exec-out run-as com.myreader cat /data/data/com.myreader/databases/myreader.db-wal > d:\develop\code\MyReader\tools\acceptance\after\myreader.db-wal
%ADB% exec-out run-as com.myreader cat /data/data/com.myreader/databases/myreader.db-shm > d:\develop\code\MyReader\tools\acceptance\after\myreader.db-shm
echo PULL_AFTER_DONE
