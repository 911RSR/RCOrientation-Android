# RCOrientation-Android

Android controller app for sending a phone's orientation to one or more STM32WB boards over Bluetooth Low Energy.

This is the companion app for [RCOrientation-STM32WB](https://github.com/911RSR/RCOrientation-STM32WB). The phone computes its own orientation, sends relative roll / pitch / yaw commands to each connected receiver, and displays servo feedback reported by the boards.

## Current status

Working prototype:

- reads phone orientation from Android's rotation-vector sensor
- lets the user zero the current pose
- streams relative roll, pitch, and yaw to every connected STM32WB
- requests a high-priority BLE connection, asks Android for 10 ms sensor samples, and sends one orientation update for each fresh sensor sample for responsive bench testing
- supports multiple connected boards
- uses a device-selection mode for paired receivers, with nearby scanning only when adding a new receiver
- receives servo-position feedback from each board
- requires BLE pairing before the encrypted control channel can operate

## Requirements

- Android phone with BLE and a rotation-vector sensor
- Android Studio for development
- companion STM32WB firmware: [RCOrientation-STM32WB](https://github.com/911RSR/RCOrientation-STM32WB)

Tested during development on a Motorola Edge 50 Neo.

## BLE protocol

Service UUID: `7B5D0000-6A2E-4D6B-9E6F-8D2B7C5A1100`

| Characteristic | Direction | Properties | Size |
| --- | --- | --- | --- |
| `0x0001` Orientation command | phone -> STM32WB | encrypted write / write without response | 8 bytes |
| `0x0002` Servo feedback | STM32WB -> phone | encrypted read / notify | 10 bytes |

Orientation command payload:

```c
int16_t roll_cdeg;
int16_t pitch_cdeg;
int16_t yaw_cdeg;
uint16_t sequence;
```

Servo feedback payload:

```c
int16_t servo_us[4];
uint16_t sequence;
```

## Current behavior

- Roll, pitch, and yaw are shown live on screen.
- `Zero` defines the current phone pose as the neutral reference.
- Device-selection mode shows paired receivers; normal operation hides that list once boards are chosen.
- The app can connect to more than one board.
- `Add receiver` opens a nearby BLE scan for first-time pairing or setup.
- Connected boards all receive the same orientation packet.
- Connected boards report their latest servo positions back to the app.
- The UI is locked to portrait mode so control gestures do not recreate the activity and drop BLE links.

## Build

Open the project in Android Studio and run it on a real phone, or build from the command line:

```powershell
.\gradlew.bat :app:assembleDebug
```

GitHub Actions also builds the Android project on every push and pull request.

## Sharing the app

For direct sharing outside Google Play, create a signed release APK in Android Studio:

```text
Build -> Generate Signed Bundle / APK -> APK
```

Keep the signing key safe; updates must be signed with the same key to install over an earlier version.

## Security note

The companion receiver requires bonded encrypted BLE access. The current first-pairing mode is intentionally simple and does not provide man-in-the-middle protection during the first pairing ceremony.

## Related project

- STM32WB firmware: [RCOrientation-STM32WB](https://github.com/911RSR/RCOrientation-STM32WB)
