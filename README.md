# RC_orientation_app

Android controller app for sending a phone's orientation to one or more STM32WB boards over Bluetooth Low Energy.

The app currently:

- reads phone orientation from Android's rotation-vector sensor
- lets the user zero the current pose
- streams relative roll, pitch, and yaw to every connected STM32WB
- supports multiple connected boards
- receives servo-position feedback from each board
- requires BLE pairing before the encrypted control channel can operate

## BLE protocol

Service UUID: `7B5D0000-6A2E-4D6B-9E6F-8D2B7C5A1100`

Characteristics:

- `0x0001` Orientation command, phone -> STM32WB, write / write without response, 8 bytes
- `0x0002` Servo feedback, STM32WB -> phone, read / notify, 8 bytes

Orientation command payload:

```c
int16_t roll_cdeg;
int16_t pitch_cdeg;
int16_t yaw_cdeg;
uint16_t sequence;
```

Servo feedback payload:

```c
int16_t servo_roll_us;
int16_t servo_pitch_us;
int16_t servo_yaw_us;
uint16_t sequence;
```

## Current behavior

- Roll, pitch, and yaw are shown live on screen.
- `Zero` defines the current phone pose as the neutral reference.
- The app can connect to more than one board and broadcast the same orientation packet to all of them.
- Connected boards report their latest servo positions back to the app.
- The UI is locked to portrait mode so control gestures do not recreate the activity and drop BLE links.

## Development notes

Built with Android Studio and Kotlin / Jetpack Compose.

The companion STM32WB firmware project is `RC_orientation_STM32WB`.
