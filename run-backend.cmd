@echo off
setlocal
pushd "%~dp0"
powershell -NoProfile -Command "$listener = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($listener) { Stop-Process -Id $listener.OwningProcess -Force }"
call mvnw.cmd -q -f apps\api\pom.xml spring-boot:run
popd