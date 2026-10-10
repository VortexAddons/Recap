/*
 * Recapper firmware: ESP32 + INMP441
 *
 * Two modes, chosen automatically at boot:
 *   SETUP  (no saved config, or button held ~2.5 s while powering on)
 *          BLE server "Recapper-XXXX". The Recap app pairs, runs a mic test (live level
 *          stream), lists WiFi networks, sends WiFi + backend credentials, verifies them.
 *   NORMAL BLE off. WiFi on. Hold the button = push-to-talk. If the backend config says
 *          "daily", the device also records speech in 15 s clips all day (simple
 *          voice-activity detection skips silence). Clips are written to flash, then
 *          uploaded by a background task, so there are no gaps while uploading.
 *
 * Pins: INMP441 WS=25 SCK=26 SD=33 L/R=GND | Button GPIO14 -> GND (INPUT_PULLUP)
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <driver/i2s.h>
#include <time.h>
#include <math.h>

#define FW_VERSION "1.0.0"

// ---- BLE UUIDs (must match the Android app) ----
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CONTROL_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"  // app -> device (write)
#define EVENT_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26a9"  // device -> app (read/notify)
static const char SEP = 0x1F;                                // field separator inside commands

// ---- Hardware / audio ----
#define PIN_WS 25
#define PIN_SCK 26
#define PIN_SD 33
#define PIN_BTN 14
#define SAMPLE_RATE 16000
#define BLOCK 512            // samples per read (32 ms)
#define GAIN_SHIFT 14        // 32-bit slot with 24-bit data: >>16 = unity, >>14 = +12 dB
#define DAILY_SEG_SEC 15
#define PTT_MAX_SEC 30
#define NUM_SLOTS 3

struct Job { uint8_t slot; uint8_t daily; uint64_t startMs; };

Preferences prefs;
String cfgSsid, cfgPass, cfgUrl, cfgKey;
bool i2sOn = false;
float noiseFloor = 80.0f;
volatile bool dailyMode = false;
volatile bool slotBusy[NUM_SLOTS] = {false, false, false};
QueueHandle_t uploadQ;

// setup-mode state
BLECharacteristic *eventChar = nullptr;
volatile bool bleConn = false;
volatile bool cmdPending = false;
String cmdBuf;
bool testing = false;
uint32_t testStart = 0;

// ---------------------------------------------------------------- I2S
bool i2sStart() {
  if (i2sOn) return true;
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate = SAMPLE_RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;  // L/R tied to GND
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 16;  // ~0.5 s of slack so flash writes never overflow
  cfg.dma_buf_len = 512;
  cfg.use_apll = false;
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = PIN_SCK;
  pins.ws_io_num = PIN_WS;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = PIN_SD;
  if (i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL) != ESP_OK) return false;
  if (i2s_set_pin(I2S_NUM_0, &pins) != ESP_OK) { i2s_driver_uninstall(I2S_NUM_0); return false; }
  i2sOn = true;
  return true;
}

void i2sStop() {
  if (!i2sOn) return;
  i2s_driver_uninstall(I2S_NUM_0);
  i2sOn = false;
}

// Reads one block -> 16-bit PCM. Returns sample count and RMS.
int readBlock(int16_t *pcm, float *rms) {
  static int32_t raw[BLOCK];
  size_t got = 0;
  i2s_read(I2S_NUM_0, raw, sizeof(raw), &got, 200 / portTICK_PERIOD_MS);
  int n = got / 4;
  double acc = 0;
  for (int i = 0; i < n; i++) {
    int32_t s = raw[i] >> GAIN_SHIFT;
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    pcm[i] = (int16_t)s;
    acc += (double)s * s;
  }
  *rms = n ? (float)sqrt(acc / n) : 0.0f;
  return n;
}

void put32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

void writeWavHeader(File &f, uint32_t dataLen) {
  uint8_t h[44] = {'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',16,0,0,0,1,0,1,0,
                   0,0,0,0,0,0,0,0,2,0,16,0,'d','a','t','a',0,0,0,0};
  put32(h + 4, 36 + dataLen);
  put32(h + 24, SAMPLE_RATE);
  put32(h + 28, SAMPLE_RATE * 2);
  put32(h + 40, dataLen);
  f.seek(0);
  f.write(h, 44);
}

String slotPath(int s) { return "/r" + String(s) + ".wav"; }

// ---------------------------------------------------------------- Networking helpers
String normalizeUrl(String u) {
  u.trim();
  while (u.endsWith("/")) u.remove(u.length() - 1);
  if (u.length() && !u.startsWith("http")) u = "https://" + u;
  return u;
}

// NOTE: setInsecure() skips certificate validation (simple for hobbyists, but a network
// attacker could read the API key). For hardening, pin a CA with setCACert().
int httpGet(const String &path, String *body = nullptr) {
  HTTPClient http;
  WiFiClientSecure sc;
  WiFiClient pc;
  String url = cfgUrl + path;
  bool ok;
  if (url.startsWith("https")) { sc.setInsecure(); ok = http.begin(sc, url); }
  else ok = http.begin(pc, url);
  if (!ok) return -1;
  http.setTimeout(15000);
  http.addHeader("Authorization", "Bearer " + cfgKey);
  int code = http.GET();
  if (code > 0 && body) *body = http.getString();
  http.end();
  return code;
}

bool timeSynced() { return time(nullptr) > 1700000000; }

void loadCfg() {
  prefs.begin("recap", true);
  cfgSsid = prefs.getString("ssid", "");
  cfgPass = prefs.getString("pass", "");
  cfgUrl = prefs.getString("url", "");
  cfgKey = prefs.getString("key", "");
  prefs.end();
}

void saveCfg() {
  prefs.begin("recap", false);
  prefs.putString("ssid", cfgSsid);
  prefs.putString("pass", cfgPass);
  prefs.putString("url", cfgUrl);
  prefs.putString("key", cfgKey);
  prefs.end();
}

// ================================================================ SETUP MODE (BLE)
void notifyEvt(const String &s) {
  Serial.println("[evt] " + s);
  if (!eventChar) return;
  eventChar->setValue((uint8_t *)s.c_str(), s.length());
  if (bleConn) eventChar->notify();
  delay(25);
}

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { bleConn = true; }
  void onDisconnect(BLEServer *) override { bleConn = false; BLEDevice::startAdvertising(); }
};

class ControlCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string v = c->getValue();
    cmdBuf = String(v.c_str());
    cmdPending = true;  // handled in loop(), never in the BLE task
  }
};

int levelFromRms(float r) {
  float db = 20.0f * log10f(r + 1.0f);
  return constrain((int)((db - 30.0f) * 2.0f), 0, 100);
}

void stopTest() {
  if (!testing) return;
  testing = false;
  i2sStop();
  notifyEvt("TEST_END");
}

void testTick() {
  static int16_t pcm[BLOCK];
  static double acc = 0;
  static int cnt = 0;
  static uint32_t last = 0;
  float rms;
  int n = readBlock(pcm, &rms);
  if (n > 0) { acc += (double)rms * rms; cnt++; }
  if (millis() - last >= 100 && cnt > 0) {
    last = millis();
    notifyEvt("LEVEL|" + String(levelFromRms((float)sqrt(acc / cnt))));
    acc = 0; cnt = 0;
  }
  if (millis() - testStart > 20000) stopTest();
}

void doScan() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  int n = WiFi.scanNetworks();
  String seen = "\n";
  int sent = 0;
  for (int i = 0; i < n && sent < 12; i++) {
    String ssid = WiFi.SSID(i);
    if (ssid.isEmpty() || seen.indexOf("\n" + ssid + "\n") >= 0) continue;
    seen += ssid + "\n";
    bool secure = WiFi.encryptionType(i) != WIFI_AUTH_OPEN;
    notifyEvt("NET|" + String(WiFi.RSSI(i)) + "|" + (secure ? "1" : "0") + "|" + ssid);
    sent++;
  }
  WiFi.scanDelete();
  notifyEvt("NETS_DONE");
}

// WIFI <SEP> ssid <SEP> password <SEP> backendUrl <SEP> apiKey
void doWifi(const String &c) {
  String f[5];
  int idx = 0, start = 0;
  for (int i = 0; i <= (int)c.length() && idx < 5; i++) {
    if (i == (int)c.length() || c[i] == SEP) { f[idx++] = c.substring(start, i); start = i + 1; }
  }
  if (idx < 5 || f[1].isEmpty()) { notifyEvt("WIFI_FAIL|Bad request from app"); return; }
  cfgSsid = f[1]; cfgPass = f[2]; cfgUrl = normalizeUrl(f[3]); cfgKey = f[4];

  notifyEvt("WIFI_CONNECTING");
  WiFi.mode(WIFI_STA);
  WiFi.disconnect(true);
  delay(200);
  WiFi.begin(cfgSsid.c_str(), cfgPass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) delay(250);
  if (WiFi.status() != WL_CONNECTED) {
    wl_status_t st = WiFi.status();
    notifyEvt(String("WIFI_FAIL|") + (st == WL_NO_SSID_AVAIL ? "Network not found" :
                                       st == WL_CONNECT_FAILED ? "Wrong password" : "Couldn't connect"));
    return;
  }
  notifyEvt("WIFI_OK|" + WiFi.localIP().toString());
  saveCfg();
  int code = httpGet("/api/ping");
  notifyEvt(code == 200 ? String("CLOUD_OK") : "CLOUD_FAIL|" + String(code));
}

void handleCommand(const String &c) {
  int sp = c.indexOf(SEP);
  String name = sp < 0 ? c : c.substring(0, sp);
  Serial.println("[cmd] " + name);
  if (name == "HELLO") notifyEvt(String("INFO|") + FW_VERSION + "|" + (cfgSsid.length() ? "1" : "0"));
  else if (name == "TEST_START") { if (i2sStart()) { testing = true; testStart = millis(); } else notifyEvt("TEST_END"); }
  else if (name == "TEST_STOP") stopTest();
  else if (name == "SCAN") doScan();
  else if (name == "WIFI") doWifi(c);
  else if (name == "FINISH") { notifyEvt("BYE"); delay(600); ESP.restart(); }
}

void startSetupMode() {
  uint64_t chip = ESP.getEfuseMac();
  char name[24];
  snprintf(name, sizeof(name), "Recapper-%04X", (uint16_t)((chip >> 32) & 0xFFFF));
  BLEDevice::init(name);
  BLEDevice::setMTU(247);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService *svc = server->createService(SERVICE_UUID);
  BLECharacteristic *ctrl = svc->createCharacteristic(CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE);
  ctrl->setCallbacks(new ControlCb());
  eventChar = svc->createCharacteristic(EVENT_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  eventChar->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.printf("Setup mode: advertising as %s\n", name);
}

void setupLoop() {
  if (cmdPending) { cmdPending = false; String c = cmdBuf; handleCommand(c); }
  if (testing) testTick(); else delay(10);
}

// ================================================================ NORMAL MODE
float vadThreshold() { float t = noiseFloor * 3.5f; return t < 250.0f ? 250.0f : t; }

int freeSlot() {
  for (int i = 0; i < NUM_SLOTS; i++) if (!slotBusy[i]) return i;
  return -1;
}

// daily=false: push-to-talk (runs while button held). daily=true: one fixed-length clip.
void recordSegment(bool daily) {
  int slot = freeSlot();
  if (slot < 0 && !daily) {  // PTT: wait briefly for an upload to free a slot
    uint32_t t = millis();
    while (slot < 0 && millis() - t < 5000) { delay(100); slot = freeSlot(); }
  }
  if (slot < 0) { delay(daily ? 500 : 0); return; }

  String path = slotPath(slot);
  LittleFS.remove(path);
  File f = LittleFS.open(path, "w");
  if (!f) { delay(500); return; }
  writeWavHeader(f, 0);

  size_t freeB = LittleFS.totalBytes() - LittleFS.usedBytes();
  uint32_t cap = freeB > 16384 ? freeB - 16384 : 0;
  uint32_t want = (uint32_t)SAMPLE_RATE * 2 * (daily ? DAILY_SEG_SEC : PTT_MAX_SEC);
  uint32_t maxData = cap < want ? cap : want;

  if (!i2sStart()) { f.close(); return; }
  uint64_t startMs = timeSynced() ? (uint64_t)time(nullptr) * 1000ULL : 0;

  static int16_t pcm[BLOCK];
  uint32_t dataLen = 0, speechBlocks = 0;
  int skip = SAMPLE_RATE / 10;  // drop first 100 ms (mic settling)
  float rms;

  while (dataLen + BLOCK * 2 <= maxData) {
    bool pressed = digitalRead(PIN_BTN) == LOW;
    if (daily && pressed) break;    // push-to-talk takes over
    if (!daily && !pressed) break;  // released
    int n = readBlock(pcm, &rms);
    if (n <= 0) continue;
    if (skip > 0) { skip -= n; continue; }
    float thr = vadThreshold();
    if (rms > thr) speechBlocks++; else noiseFloor += (rms - noiseFloor) * 0.02f;
    f.write((uint8_t *)pcm, n * 2);
    dataLen += n * 2;
  }

  writeWavHeader(f, dataLen);
  f.close();
  if (!dailyMode) i2sStop();

  bool keep = daily ? (speechBlocks >= 16 && dataLen >= SAMPLE_RATE * 2)
                    : (dataLen >= SAMPLE_RATE);  // PTT: at least 0.5 s
  if (keep) {
    slotBusy[slot] = true;
    Job j = {(uint8_t)slot, (uint8_t)daily, startMs};
    xQueueSend(uploadQ, &j, 0);
    Serial.printf("Queued %s clip: %u bytes\n", daily ? "daily" : "ptt", (unsigned)dataLen);
  } else {
    LittleFS.remove(path);
  }
}

int uploadFile(const String &path, bool daily, uint64_t startMs) {
  if (WiFi.status() != WL_CONNECTED) {
    WiFi.reconnect();
    uint32_t t = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t < 15000) vTaskDelay(250 / portTICK_PERIOD_MS);
    if (WiFi.status() != WL_CONNECTED) return -1;
  }
  File f = LittleFS.open(path, "r");
  if (!f) return 200;  // nothing to send

  HTTPClient http;
  WiFiClientSecure sc;
  WiFiClient pc;
  String url = cfgUrl + "/api/transcribe?source=" + (daily ? "device-daily" : "device-ptt");
  bool ok;
  if (url.startsWith("https")) { sc.setInsecure(); ok = http.begin(sc, url); }
  else ok = http.begin(pc, url);
  if (!ok) { f.close(); return -1; }

  char ts[24];
  snprintf(ts, sizeof(ts), "%llu", (unsigned long long)startMs);
  http.setTimeout(60000);  // transcription can take a while
  http.addHeader("Authorization", "Bearer " + cfgKey);
  http.addHeader("Content-Type", "audio/wav");
  http.addHeader("X-Recorded-At", ts);
  int code = http.sendRequest("POST", &f, f.size());
  Serial.printf("Upload %u bytes -> HTTP %d\n", (unsigned)f.size(), code);
  http.end();
  f.close();
  return code;
}

void pollConfig() {
  String body;
  if (httpGet("/api/config", &body) == 200) {
    bool d = body.indexOf("\"daily\"") >= 0;
    if (d != dailyMode) { dailyMode = d; Serial.printf("Mode -> %s\n", d ? "daily" : "push-to-talk"); }
  }
}

void uploadTask(void *) {
  Job j;
  uint32_t lastPoll = 0;
  pollConfig();
  for (;;) {
    if (xQueueReceive(uploadQ, &j, 30000 / portTICK_PERIOD_MS) == pdTRUE) {
      String path = slotPath(j.slot);
      for (int attempt = 0; attempt < 5; attempt++) {
        int code = uploadFile(path, j.daily, j.startMs);
        if (code >= 200 && code < 500) break;  // success, or a rejection retrying won't fix
        vTaskDelay(3000 / portTICK_PERIOD_MS);
      }
      LittleFS.remove(path);
      slotBusy[j.slot] = false;
    }
    if (millis() - lastPoll > 30000) { lastPoll = millis(); pollConfig(); }
  }
}

void startNormalMode() {
  for (int i = 0; i < NUM_SLOTS; i++) LittleFS.remove(slotPath(i));
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(cfgSsid.c_str(), cfgPass.c_str());
  uint32_t t = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t < 20000) delay(250);
  Serial.printf("WiFi: %s\n", WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString().c_str() : "not connected yet");
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  uploadQ = xQueueCreate(NUM_SLOTS, sizeof(Job));
  xTaskCreatePinnedToCore(uploadTask, "upload", 16384, NULL, 1, NULL, 0);
}

void normalLoop() {
  if (digitalRead(PIN_BTN) == LOW) {
    delay(25);  // debounce
    if (digitalRead(PIN_BTN) == LOW) { recordSegment(false); return; }
  }
  if (dailyMode) { recordSegment(true); return; }
  delay(20);
}

// ================================================================ Arduino entry points
bool setupMode = false;

void setup() {
  Serial.begin(115200);
  pinMode(PIN_BTN, INPUT_PULLUP);
  LittleFS.begin(true);
  loadCfg();

  bool forced = false;
  if (digitalRead(PIN_BTN) == LOW) {  // button held at power-on?
    uint32_t t = millis();
    while (digitalRead(PIN_BTN) == LOW && millis() - t < 2500) delay(20);
    forced = digitalRead(PIN_BTN) == LOW;
    while (digitalRead(PIN_BTN) == LOW) delay(10);  // wait for release
  }
  setupMode = forced || cfgSsid.isEmpty() || cfgUrl.isEmpty() || cfgKey.isEmpty();
  if (setupMode) startSetupMode(); else startNormalMode();
}

void loop() {
  if (setupMode) setupLoop(); else normalLoop();
}
