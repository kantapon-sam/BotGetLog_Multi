@echo off
cd /d "%~dp0dist"
java -Xms256m -Xmx2048m -XX:+UseG1GC -jar "Bot Tool Launcher.jar"
