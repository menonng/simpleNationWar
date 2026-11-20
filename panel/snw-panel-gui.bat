@echo off
rem 서버 관리 창(GUI 버전).
start "" javaw -jar "%~dp0snw-panel.jar" "%~dp0panel.properties" --gui %*
