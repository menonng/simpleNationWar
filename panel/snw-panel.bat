@echo off
rem 서버 관리 CLI (Paper 콘솔처럼). Windows 터미널이나 명령 프롬프트에서 열린다.
rem panel.properties 가 없으면 처음 실행할 때 만든다. --autostart 를 붙이면 바로 서버를 켠다.
cd /d "%~dp0"
java -jar "%~dp0snw-panel.jar" "%~dp0panel.properties" %*
pause
