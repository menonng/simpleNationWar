@echo off
rem 서버 관리 창 실행. panel.properties 가 없으면 처음 실행할 때 만든다.
start "" javaw -jar "%~dp0snw-panel.jar" "%~dp0panel.properties" %*
