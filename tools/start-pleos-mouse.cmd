@echo off
"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd Pleos_Connect_v2.1.0 -netdelay none -netspeed full -no-snapstorage -feature VirtioTablet,-VirtioMouse
