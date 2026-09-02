@echo off
setlocal EnableExtensions

rem Build a self-contained TinyCC release payload on Windows.
rem Usage: package-windows.cmd ^<x86_64^|arm64^> ^<zip-path^>
if "%~2"=="" (
  echo usage: %~nx0 ^<x86_64^|arm64^> ^<zip-path^>
  exit /b 2
)

set "TARGET=%~1"
set "ARCHIVE=%~f2"
set "ROOT=%CD%"
set "PAYLOAD=%ROOT%\.release\%TARGET%\tinycc"
set "DEFINES=-DTCC_TARGET_PE"
if "%TARGET%"=="x86_64" set "DEFINES=%DEFINES% -DTCC_TARGET_X86_64"
if "%TARGET%"=="arm64" set "DEFINES=%DEFINES% -DTCC_TARGET_ARM64"
if "%DEFINES%"=="-DTCC_TARGET_PE" (
  echo unsupported target: %TARGET%
  exit /b 2
)

if exist "%ROOT%\.release\%TARGET%" rmdir /s /q "%ROOT%\.release\%TARGET%"
call win32\build-tcc.bat -c cl -t %TARGET%
if errorlevel 1 exit /b %errorlevel%

rem Windows builds libtcc.dll by default.  Also provide a static MSVC archive
rem for embedders that do not want a DLL dependency.
cl /nologo /O2 /W2 /MT /GS- /c libtcc.c /I. %DEFINES% /Fo:win32\libtcc-static.obj
if errorlevel 1 exit /b %errorlevel%
lib /nologo /OUT:win32\libtcc-static.lib win32\libtcc-static.obj
if errorlevel 1 exit /b %errorlevel%

if "%JAVA_HOME%"=="" (
  echo JAVA_HOME must point to a JDK to build the JNI bridge
  exit /b 2
)
cl /nologo /LD /O2 /W2 /MT /GS- bindings\native\tcc_jni.c /I. /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" win32\libtcc.lib /link /OUT:win32\libtinycc_jni.dll
if errorlevel 1 exit /b %errorlevel%

mkdir "%PAYLOAD%\bin" "%PAYLOAD%\include" "%PAYLOAD%\bin\lib"
copy /y win32\tcc.exe "%PAYLOAD%\bin\tcc.exe" >nul
copy /y win32\libtcc.dll "%PAYLOAD%\bin\libtcc.dll" >nul
copy /y win32\libtinycc_jni.dll "%PAYLOAD%\bin\libtinycc_jni.dll" >nul
copy /y win32\libtcc-static.lib "%PAYLOAD%\bin\libtcc-static.lib" >nul
xcopy /e /i /q /y win32\include "%PAYLOAD%\bin\include" >nul
xcopy /e /i /q win32\lib "%PAYLOAD%\bin\lib" >nul
copy /y libtcc.h "%PAYLOAD%\include\libtcc.h" >nul
copy /y COPYING "%PAYLOAD%\COPYING" >nul
copy /y README "%PAYLOAD%\README" >nul
copy /y VERSION "%PAYLOAD%\VERSION" >nul

rem libtcc.dll derives its private runtime location from its own directory.
"%PAYLOAD%\bin\tcc.exe" -run examples\ex1.c
if errorlevel 1 exit /b %errorlevel%

for %%I in ("%ARCHIVE%") do if not exist "%%~dpI" mkdir "%%~dpI"
if exist "%ARCHIVE%" del /q "%ARCHIVE%"
powershell -NoProfile -Command "Compress-Archive -Path '%PAYLOAD%' -DestinationPath '%ARCHIVE%'"
