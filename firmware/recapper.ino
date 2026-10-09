#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <driver/i2s.h>

const char* WIFI_SSID = "YOUR_WIFI_SSID";
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";
const char* WORKER_URL = "https://recap-backend.YOUR_SUBDOMAIN.workers.dev/api/audio";

#define BUTTON_PIN 14
#define I2S_WS 25
#define I2S_SD 33
#define I2S_SCK 26
#define I2S_PORT I2S_NUM_0

const int SAMPLE_RATE = 16000;
const int BUFFER_SIZE = 1024;
uint8_t audioBuffer[BUFFER_SIZE];

uint8_t* recordBuffer = NULL;
size_t recordedBytes = 0;
const size_t MAX_RECORD_BYTES = SAMPLE_RATE * 2 * 30; // 30 second limit

bool isRecording = false;
WebServer server(80);

void initI2S() {
  i2s_config_t i2s_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 64,
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

void sendAudioToCloudflare(uint8_t* data, size_t len, const char* modeType) {
  if (WiFi.status() != WL_CONNECTED || len == 0) return;

  HTTPClient http;
  String targetUrl = String(WORKER_URL) + "?mode=" + modeType;
  http.begin(targetUrl);
  http.addHeader("Content-Type", "application/octet-stream");

  int httpCode = http.POST(data, len);
  Serial.printf("Recapper posted audio. Response Code: %d\n", httpCode);
  http.end();
}

void handleRemoteRecord() {
  if (!server.hasArg("action")) {
    server.send(400, "text/plain", "Missing action");
    return;
  }
  String action = server.arg("action");

  if (action == "start") {
    isRecording = true;
    recordedBytes = 0;
    server.send(200, "text/plain", "Recapper Started Recording");
  } else if (action == "stop") {
    isRecording = false;
    server.send(200, "text/plain", "Recapper Stopped Recording");
    sendAudioToCloudflare(recordBuffer, recordedBytes, "recapper_remote");
    recordedBytes = 0;
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  recordBuffer = (uint8_t*) malloc(MAX_RECORD_BYTES);

  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nRecapper Connected! IP: " + WiFi.localIP().toString());

  initI2S();

  server.on("/api/record", HTTP_POST, handleRemoteRecord);
  server.begin();
}

void loop() {
  server.handleClient();

  // Local physical push button handling
  if (digitalRead(BUTTON_PIN) == LOW) {
    if (!isRecording) {
      isRecording = true;
      recordedBytes = 0;
    }

    size_t bytesRead = 0;
    i2s_read(I2S_PORT, audioBuffer, BUFFER_SIZE, &bytesRead, portMAX_DELAY);

    if (recordedBytes + bytesRead < MAX_RECORD_BYTES) {
      memcpy(recordBuffer + recordedBytes, audioBuffer, bytesRead);
      recordedBytes += bytesRead;
    }
  } else if (isRecording && !server.hasClient()) {
    isRecording = false;
    sendAudioToCloudflare(recordBuffer, recordedBytes, "recapper_hardware_button");
    recordedBytes = 0;
  }

  delay(10);
}
