import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:permission_handler/permission_handler.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const RecapApp());
}

class RecapApp extends StatelessWidget {
  const RecapApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Recap',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6366F1),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const RootHandler(),
    );
  }
}

class RootHandler extends StatefulWidget {
  const RootHandler({super.key});

  @override
  State<RootHandler> createState() => _RootHandlerState();
}

class _RootHandlerState extends State<RootHandler> {
  bool _isConfigured = false;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _checkSetup();
  }

  Future<void> _checkSetup() async {
    final prefs = await SharedPreferences.getInstance();
    final url = prefs.getString('worker_url');
    setState(() {
      _isConfigured = url != null && url.isNotEmpty;
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return _isConfigured ? const HomeScreen() : const OnboardingScreen();
  }
}

// ONBOARDING & BLE PROVISIONING FLOW
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _ssidController = TextEditingController();
  final _passController = TextEditingController();
  final _urlController = TextEditingController();

  BluetoothDevice? _targetDevice;
  BluetoothCharacteristic? _credChar;
  BluetoothCharacteristic? _statusChar;

  bool _isScanning = false;
  bool _isConnecting = false;
  bool _isProvisioning = false;
  String _statusMessage = 'Searching for Recapper hardware...';
  int _step = 1; // 1: Pair, 2: WiFi Details, 3: Voice Check

  @override
  void initState() {
    super.initState();
    _startBLEScan();
  }

  Future<void> _startBLEScan() async {
    await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.locationWhenInUse
    ].request();

    setState(() {
      _isScanning = true;
      _statusMessage = 'Looking for Recapper hardware nearby...';
    });

    FlutterBluePlus.startScan(timeout: const Duration(seconds: 10));

    FlutterBluePlus.scanResults.listen((results) async {
      for (ScanResult r in results) {
        if (r.device.platformName == 'Recapper-Hardware') {
          FlutterBluePlus.stopScan();
          _connectToDevice(r.device);
          break;
        }
      }
    });
  }

  Future<void> _connectToDevice(BluetoothDevice device) async {
    setState(() {
      _isScanning = false;
      _isConnecting = true;
      _statusMessage = 'Found hardware! Connecting via BLE...';
      _targetDevice = device;
    });

    await device.connect();
    List<BluetoothService> services = await device.discoverServices();

    for (var service in services) {
      if (service.uuid.toString() == "4fafc201-1fb5-459e-8fcc-c5c9c331914b") {
        for (var char in service.characteristics) {
          if (char.uuid.toString() == "beb5483e-36e1-4688-b7f5-ea07361b26a8") {
            _credChar = char;
          }
          if (char.uuid.toString() == "8ec8f08e-0571-4f6d-9491-c6d30284f1a1") {
            _statusChar = char;
            await _statusChar!.setNotifyValue(true);
            _statusChar!.onValueReceived.listen(_handleBLEStatus);
          }
        }
      }
    }

    setState(() {
      _isConnecting = false;
      _step = 2;
    });
  }

  void _handleBLEStatus(List<int> value) async {
    String status = utf8.decode(value);
    if (status.startsWith("CONNECTED")) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('worker_url', _urlController.text.trim());

      setState(() {
        _isProvisioning = false;
        _step = 3; // Move to Voice Match test
      });
    } else if (status == "FAILED") {
      setState(() {
        _isProvisioning = false;
        _statusMessage = 'Wi-Fi Connection Failed. Check password.';
      });
    }
  }

  Future<void> _sendProvisioning() async {
    if (_credChar == null) return;

    setState(() {
      _isProvisioning = true;
      _statusMessage = 'Sending Wi-Fi credentials over BLE...';
    });

    String payload =
        "${_ssidController.text.trim()}|${_passController.text.trim()}|${_urlController.text.trim()}/api/audio";
    await _credChar!.write(utf8.encode(payload));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Recapper Onboarding')),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              LinearProgressIndicator(value: _step / 3),
              const SizedBox(height: 32),
              if (_step == 1) ...[
                const Icon(Icons.bluetooth_searching, size: 64, color: Color(0xFF6366F1)),
                const SizedBox(height: 24),
                Text(_statusMessage, textAlign: TextAlign.center, style: const TextStyle(fontSize: 18)),
                const SizedBox(height: 24),
                if (_isScanning || _isConnecting)
                  const Center(child: CircularProgressIndicator())
                else
                  ElevatedButton(
                    onPressed: _startBLEScan,
                    child: const Text('Retry BLE Search'),
                  )
              ],
              if (_step == 2) ...[
                const Text('Connect Recapper to Wi-Fi', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                const SizedBox(height: 16),
                TextField(
                  controller: _ssidController,
                  decoration: const InputDecoration(labelText: 'Wi-Fi SSID (Name)', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _passController,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: 'Wi-Fi Password', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _urlController,
                  decoration: const InputDecoration(
                    labelText: 'Cloudflare Worker URL',
                    hintText: 'https://recap.subdomain.workers.dev',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 24),
                _isProvisioning
                    ? const Center(child: CircularProgressIndicator())
                    : ElevatedButton(
                        onPressed: _sendProvisioning,
                        style: ElevatedButton.styleFrom(minimumSize: const Size.fromHeight(50)),
                        child: const Text('Connect Hardware'),
                      ),
              ],
              if (_step == 3) ...[
                const Icon(Icons.check_circle, size: 72, color: Colors.green),
                const SizedBox(height: 16),
                const Text('Hardware Connected!', textAlign: TextAlign.center, style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                const Text(
                  'Voice Test: Press and hold the physical button on your Recapper, speak a short test note, and release.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
                const SizedBox(height: 32),
                ElevatedButton(
                  onPressed: () {
                    Navigator.of(context).pushReplacement(
                      MaterialPageRoute(builder: (_) => const HomeScreen()),
                    );
                  },
                  style: ElevatedButton.styleFrom(minimumSize: const Size.fromHeight(50)),
                  child: const Text('Finish Setup & Go to Feed'),
                )
              ]
            ],
          ),
        ),
      ),
    );
  }
}

// HOME FEED & SEARCH SCREEN
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  List<dynamic> _notes = [];
  bool _isLoading = true;
  final _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _fetchNotes();
  }

  Future<void> _fetchNotes({String query = ''}) async {
    setState(() => _isLoading = true);
    final prefs = await SharedPreferences.getInstance();
    final baseUrl = prefs.getString('worker_url') ?? '';
    
    // Clean base URL for endpoints
    final host = baseUrl.replaceAll('/api/audio', '');
    final endpoint = query.isEmpty 
        ? '$host/api/notes' 
        : '$host/api/search?q=${Uri.encodeComponent(query)}';

    try {
      final response = await http.get(Uri.parse(endpoint));
      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        setState(() {
          _notes = query.isEmpty ? data['notes'] : data['results'];
          _isLoading = false;
        });
      }
    } catch (e) {
      setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Recap Feed'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () async {
              final prefs = await SharedPreferences.getInstance();
              await prefs.clear();
              if (mounted) {
                Navigator.of(context).pushReplacement(
                  MaterialPageRoute(builder: (_) => const OnboardingScreen()),
                );
              }
            },
          )
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: 'Search voice notes by meaning...',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.arrow_forward),
                  onPressed: () => _fetchNotes(query: _searchController.text),
                ),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onSubmitted: (val) => _fetchNotes(query: val),
            ),
          ),
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : RefreshIndicator(
                    onRefresh: () => _fetchNotes(),
                    child: _notes.isEmpty
                        ? const Center(child: Text('No voice notes recorded yet.'))
                        : ListView.builder(
                            itemCount: _notes.length,
                            itemBuilder: (context, index) {
                              final item = _notes[index];
                              final text = item['text'] ?? item['item']?['metadata']?['text'] ?? '';
                              final mode = item['mode'] ?? 'PTT';
                              return Card(
                                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                                child: ListTile(
                                  leading: CircleAvatar(
                                    child: Icon(mode == 'PTT' ? Icons.mic : Icons.loop),
                                  ),
                                  title: Text(text),
                                  subtitle: Text('Mode: $mode'),
                                ),
                              );
                            },
                          ),
                  ),
          ),
        ],
      ),
    );
  }
}
