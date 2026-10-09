#include <WiFi.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <driver/i2s.h>
#include <HTTPClient.h>

#define I2S_WS 25
#define I2S_SD 33
#define I2S_SCK 26
#define I2S_PORT I2S_NUM_0
#define BUTTON_PIN 14

#define SERVICE_UUID           "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_CREDENTIALS_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CHAR_STATUS_UUID      "8ec8f08e-0571-4f6d-9491-c6d30284f1a1"

Preferences prefs;
BLEServer* pServer = nullptr;
BLECharacteristic* pStatusChar = nullptr;

bool deviceConnected = false;
bool wifiConfigured = false;
String wifiSSID = "";
String wifiPASS = "";
String workerURL = "";

// WAV Header struct for 16kHz mono 16-bit PCM
struct WAVHeader {
  char riff[4] = {'R', 'I', 'F', 'F'};
  uint32_t chunkSize;
  char wave[4] = {'W', 'A', 'V', 'E'};
  char fmt[4] = {'f', 'm', 't', ' '};
  uint32_t subchunk1Size = 16;
  uint16_t audioFormat = 1;
  uint16_t numChannels = 1;
  uint32_t sampleRate = 16000;
  uint32_t byteRate = 32000;
  uint16_t blockAlign = 2;
  uint16_t bitsPerSample = 16;
  char data[4] = {'d', 'a', 't', 'a'};
  uint32_t subchunk2Size;
};

class ServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
  }
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    BLEDevice::startAdvertising();
  }
};

class CredentialsCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String rxValue = pCharacteristic->getValue().c_str();
    if (rxValue.length() > 0) {
      // Expected format: SSID|PASSWORD|WORKER_URL
      int firstPipe = rxValue.indexOf('|');
      int secondPipe = rxValue.indexOf('|', firstPipe + 1);
      
      if (firstPipe != -1 && secondPipe != -1) {
        wifiSSID = rxValue.substring(0, firstPipe);
        wifiPASS = rxValue.substring(firstPipe + 1, secondPipe);
        workerURL = rxValue.substring(secondPipe + 1);

        prefs.begin("recapper", false);
        prefs.putString("ssid", wifiSSID);
        prefs.putString("pass", wifiPASS);
        prefs.putString("url", workerURL);
        prefs.end();

        WiFi.begin(wifiSSID.c_str(), wifiPASS.c_str());
        int attempts = 0;
        while (WiFi.status() != WL_CONNECTED && attempts < 20) {
          delay(500);
          attempts++;
        }

        if (WiFi.status() == WL_CONNECTED) {
          pStatusChar->setValue("CONNECTED|" + WiFi.localIP().toString());
          pStatusChar->notify();
          wifiConfigured = true;
        } else {
          pStatusChar->setValue("FAILED");
          pStatusChar->notify();
        }
      }
    }
  }
};

void initI2S() {
  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = 16000,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = 512,
    .use_apll = false
  };
  
  i2s_pin_config_t pin_config = {
    .bck_io_num = I2S_SCK,
    .ws_io_num = I2S_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_SD
  };
  
  i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
  i2s_set_pin(I2S_PORT, &pin_config);
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  initI2S();

  prefs.begin("recapper", true);
  wifiSSID = prefs.getString("ssid", "");
  wifiPASS = prefs.getString("pass", "");
  workerURL = prefs.getString("url", "");
  prefs.end();

  BLEDevice::init("Recapper-Hardware");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());
  
  BLEService *pService = pServer->createService(SERVICE_UUID);
  
  BLECharacteristic *pCredChar = pService->createCharacteristic(
                                   CHAR_CREDENTIALS_UUID,
                                   BLECharacteristic::PROPERTY_WRITE
                                 );
  pCredChar->setCallbacks(new CredentialsCallbacks());

  pStatusChar = pService->createCharacteristic(
                  CHAR_STATUS_UUID,
                  BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
                );
  pStatusChar->addDescriptor(new BLE2902());

  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  if (wifiSSID.length() > 0) {
    WiFi.begin(wifiSSID.c_str(), wifiPASS.c_str());
    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 15) {
      delay(500);
      attempts++;
    }
    if (WiFi.status() == WL_CONNECTED) {
      wifiConfigured = true;
    }
  }
}

void recordAndSend() {
  size_t bufferLen = 16000 * 2 * 5; // 5 seconds of 16bit PCM audio
  uint8_t* pcmBuffer = (uint8_t*) malloc(bufferLen);
  if (!pcmBuffer) return;

  size_t bytesRead = 0;
  size_t totalBytesRead = 0;
  
  while (digitalRead(BUTTON_PIN) == LOW && totalBytesRead < bufferLen) {
    int32_t rawSample[64];
    size_t readNow = 0;
    i2s_read(I2S_PORT, &rawSample, sizeof(rawSample), &readNow, portMAX_DELAY);
    
    for (int i = 0; i < (readNow / 4); i++) {
      int16_t sample16 = rawSample[i] >> 14; // Convert 32-bit to 16-bit PCM
      pcmBuffer[totalBytesRead++] = sample16 & 0xFF;
      pcmBuffer[totalBytesRead++] = (sample16 >> 8) & 0xFF;
      if (totalBytesRead >= bufferLen) break;
    }
  }

  if (totalBytesRead > 0 && WiFi.status() == WL_CONNECTED) {
    WAVHeader header;
    header.subchunk2Size = totalBytesRead;
    header.chunkSize = 36 + totalBytesRead;

    HTTPClient http;
    http.begin(workerURL);
    http.addHeader("Content-Type", "audio/wav");

    size_t payloadSize = sizeof(WAVHeader) + totalBytesRead;
    uint8_t* payload = (uint8_t*) malloc(payloadSize);
    memcpy(payload, &header, sizeof(WAVHeader));
    memcpy(payload + sizeof(WAVHeader), pcmBuffer, totalBytesRead);

    http.POST(payload, payloadSize);
    http.end();
    free(payload);
  }

  free(pcmBuffer);
}

void loop() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    delay(50); // Debounce
    if (digitalRead(BUTTON_PIN) == LOW) {
      recordAndSend();
    }
  }
  delay(10);
}
