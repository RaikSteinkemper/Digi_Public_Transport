# Pi Beacon (optional)

This folder contains a placeholder for running a Raspberry Pi as a BLE beacon advertising `BUS_4711`.

Simple Node.js approach (requires `bleno` and Linux with BlueZ):

1. On the Pi: `sudo apt install build-essential libcap-dev` and `npm install -g bleno`
2. Run `node beacon.js` (may need `sudo`).

This is optional for the PoC — the passenger app can simulate presence with the buttons.
