# Recap + Recapper

**Recap** is an Android app that records and transcribes notes with your phone's mic and lets you search them with AI.
**Recapper** is an optional ESP32 + INMP441 pocket recorder (push-to-talk, or "Daily" mode that listens all day) that sends its audio to the same backend.

```
recap/
  worker/     Cloudflare Worker: transcription, storage, AI search
  firmware/   ESP32 firmware (PlatformIO) + release.py
  flasher/    Static website that flashes the ESP32 from the browser
  android/    Recap app (Kotlin, Jetpack Compose, Material 3)
```

How it fits together: the app creates a random API key. During setup the app gives the Recapper your WiFi, your Worker URL and that key over Bluetooth. After that the Recapper uploads WAV clips straight to your Worker, which transcribes them (Workers AI Whisper), stores text and search vectors, and the app reads them back. Daily/push mode is stored in the Worker and polled by the device about every 30 s.

You need: a free Cloudflare account, Node.js 18+, Python 3.8+, Android Studio, an Android 13+ phone, a standard 4 MB ESP32 dev board.

---

## 1. Deploy the backend (once)

```bash
cd worker
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler prints a URL like `https://recap-backend.<your-subdomain>.workers.dev`. Keep it; the app asks for it.
Check it: open the URL in a browser, it says "Recap backend is running."

Live logs while testing: `npx wrangler tail`

Cost note: Workers AI has a daily free allowance. Push-to-talk and phone notes fit easily. Daily mode transcribes lots of clips and can exceed the free tier on busy days; paid usage is cheap but not zero.

## 2. Build the firmware on your PC (replaces the GitHub Action)

```bash
pip install platformio
cd firmware
python release.py
```

`release.py` runs `pio run` and copies `bootloader.bin`, `partitions.bin`, `boot_app0.bin`, `firmware.bin` and a `manifest.json` into `flasher/firmware/`. Run it again whenever you change the firmware (bump `FW_VERSION` in `src/main.cpp`).

If you use the VS Code PlatformIO extension instead of pip, `release.py` finds its `pio` automatically.

## 3. Host the flasher website

Web flashing needs HTTPS or localhost.

- **Test locally:** `cd flasher && python -m http.server 8000`, then open `http://localhost:8000` in Chrome or Edge.
- **Publish for others (free):** `npx wrangler pages deploy flasher --project-name recapper-flasher` (or drop the `flasher/` folder on GitHub Pages / Netlify).

Open the page, plug in the ESP32, click **Install Recapper firmware**, pick the serial port. Tick "erase" for a first install.

## 4. Build the Android app

1. Open the `android/` folder in Android Studio and let Gradle sync (it downloads Gradle 8.9 itself).
2. Plug in your phone (USB debugging on) and press Run, or use **Build > Build APK(s)** and install the APK.

## 5. First-run setup

1. Open Recap, tap the gear, paste your Worker URL, Save.
2. Wire the mic: INMP441 **WS to GPIO25, SCK to GPIO26, SD to GPIO33, L/R to GND**, VDD to 3.3 V, GND to GND. Button between **GPIO14 and GND**.
3. Recapper tab, then **Set up Recapper**. The app walks through:
   1. check the backend URL
   2. **Find my Recapper**: Android's system pairing sheet finds it automatically (no manual Bluetooth screens)
   3. mic test: say "Hey Recap, testing one two three" and the orb reacts to your voice
   4. pick your WiFi from the list the device scans, enter the password
   5. the device verifies WiFi and your backend, then you tap Finish and it restarts into normal mode
4. Hold the button to record. Release to send. The note shows up in the Notes tab a few seconds later.
5. Recapper tab, **Daily** mode: records speech all day in 15 s clips and skips silence.

**Re-run setup:** hold the button while plugging the ESP32 in for about 3 seconds.

## Limits and honest notes

- Needs Android 13+ (it uses the Companion Device Manager APIs). iOS isn't supported.
- Phone notes auto-stop at 5 minutes. Device clips are 15 s (daily) or up to 30 s (push-to-talk).
- Audio is transcribed and thrown away; only text is stored.
- The device skips TLS certificate validation (`setInsecure()`) to keep setup simple. Pin a CA for hardening.
- The ESP32 stays on WiFi in normal mode, so run it from USB or a power bank, not a tiny battery.
- Anyone with your API key can read your notes. It lives in the app's private storage and on the device.
- I couldn't compile or run any of this where I wrote it. Expect to fix a typo or two on first build, and tell me the exact error if you do.
