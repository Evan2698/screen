# Screen

A remote screen casting project for Android devices, designed for Tesla in-car browser scenarios. Once connected through a mobile hotspot, the car browser can display the phone's screen in real time and support tap, swipe, and key interactions.

This project uses a WebSocket + JPEG image streaming approach, without relying on WebRTC or MJPEG. It is well-suited for low-bandwidth, low-latency in-vehicle environments. It has been tested and verified on Tesla Model Y vehicles from 2023 to 2025.

## Project Overview

- Real-time screen capture on Android
- Low-latency image streaming
- Direct access from the in-car browser
- Touch and key control via Accessibility Service
- Compatible with Android 14 and above

## Features

- Use MediaProjection to capture the screen
- Compress frame data into JPEG and send it over WebSocket
- Render the stream in the browser using Canvas
- Forward browser clicks, dragging, and key events back to Android
- Keep the casting session alive using a foreground service
- Support in-car remote access via hotspot networking

## Use Cases

- In-car screen display
- Remote viewing of a phone screen
- Tesla browser-based screen casting and control
- Lightweight image transmission in low-bandwidth environments

## Requirements

- Android 14 or newer
- Mobile device with hotspot capability
- Tesla vehicle can connect to the phone hotspot
- Required permissions:
  - Screen recording permission
  - Foreground service permission
  - Accessibility service permission

## Quick Start

### 1. Turn on the phone hotspot

Enable the mobile hotspot and make sure the network is stable and available.

### 2. Connect the Tesla to the hotspot

Connect the vehicle to the phone hotspot and enable options such as "keep network connected while driving" to avoid disconnects during operation.

### 3. Start the app

- Open the app
- Grant screen recording permission
- If prompted, enable the app's Accessibility Service in system settings
- Start the screen casting service

### 4. Open the casting address

In the car browser, visit:

http://9.9.9.9:8080

If the actual IP differs, use the address displayed inside the app.

### 5. Connect and use it

Click the Connect button on the page to begin screen casting. The interface supports basic tap, drag, and key interactions.

## Technical Implementation

Core modules:

- ScreenCaptureService: handles screen capture, foreground service management, and web server setup
- WebServer: provides HTTP and WebSocket services
- TouchAccessibilityService: converts browser input events into Android touch and key actions
- MainActivity: main UI for controlling service start/stop and displaying status

Data flow:

1. Android obtains screen frames through MediaProjection
2. Frames are compressed into JPEG images
3. Images are pushed to the browser via WebSocket in real time
4. The browser renders the stream on a Canvas
5. Input interactions are sent back to Android
6. Accessibility Service translates these events into real touch and key gestures

## Build Instructions

### Android Studio

1. Open the project
2. Wait for Gradle sync to finish
3. Select a target device
4. Run the app

### Command Line

```bash
./gradlew assembleDebug
```

The built APK is generated under app/build/outputs/apk/.

## Notes and Warnings

- This is a customized casting solution for in-vehicle scenarios, not a universal cross-platform screen mirroring tool
- Make sure the hotspot connection is stable and the vehicle can reach the target address before use
- If the page cannot connect, check:
  - whether screen recording permission has been granted
  - whether Accessibility Service is enabled
  - whether the foreground service is running
  - whether the hotspot connection is stable
- Because the stream is compressed, image quality and frame rate are affected by network conditions

## Screenshots

![Casting result](screenshot/screen2.png)

![Phone app interface](screenshot/screen.jpg)

## License

This project is intended for learning, research, and personal development use only.

## Notes

The project is designed to turn an Android device into a lightweight remote screen-casting endpoint suitable for Tesla-like in-vehicle environments. It can be further enhanced with:

- higher-resolution output
- more stable network reconnection
- richer interaction commands
- better frame rate and performance tuning
