import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:record/record.dart';

void main() {
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
        useMaterial3: true,
        colorSchemeSeed: Colors.indigo,
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.indigo,
        brightness: Brightness.dark,
      ),
      themeMode: ThemeMode.system,
      home: const RecapHomeScreen(),
    );
  }
}

class RecapHomeScreen extends StatefulWidget {
  const RecapHomeScreen({super.key});

  @override
  State<RecapHomeScreen> createState() => _RecapHomeScreenState();
}

class _RecapHomeScreenState extends State<RecapHomeScreen> {
  // Cloudflare Worker Live Backend API URL
  final String cloudflareWorkerUrl = "https://recap-backend.YOUR_SUBDOMAIN.workers.dev";
  
  // Local IP of the ESP32 "Recapper" device when connected
  final String recapperIp = "http://192.168.1.150";

  final AudioRecorder _phoneRecorder = AudioRecorder();
  
  bool _useRecapperHardware = false;
  bool _isRecording = false;
  bool _isLoading = true;
  List<dynamic> _notes = [];
  String? _currentRecordPath;

  @override
  void initState() {
    super.initState();
    _fetchNotes();
  }

  Future<void> _fetchNotes() async {
    setState(() => _isLoading = true);
    try {
      final res = await http.get(Uri.parse('$cloudflareWorkerUrl/api/notes'));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        setState(() {
          _notes = data['notes'] ?? [];
          _isLoading = false;
        });
      }
    } catch (_) {
      setState(() => _isLoading = false);
    }
  }

  // --- PHONE MICROPHONE RECORDING LOGIC ---
  Future<void> _startPhoneRecording() async {
    final status = await Permission.microphone.request();
    if (status != PermissionStatus.granted) return;

    final Directory tempDir = await getTemporaryDirectory();
    _currentRecordPath = '${tempDir.path}/recap_${DateTime.now().millisecondsSinceEpoch}.m4a';

    await _phoneRecorder.start(
      const RecordConfig(encoder: AudioEncoder.aacLc, sampleRate: 16000),
      path: _currentRecordPath!,
    );
    setState(() => _isRecording = true);
  }

  Future<void> _stopPhoneRecordingAndSend() async {
    final path = await _phoneRecorder.stop();
    setState(() => _isRecording = false);

    if (path != null && File(path).existsSync()) {
      final bytes = await File(path).readAsBytes();
      
      // Upload recorded audio to Cloudflare Worker
      final res = await http.post(
        Uri.parse('$cloudflareWorkerUrl/api/audio?mode=phone_mic'),
        headers: {'Content-Type': 'application/octet-stream'},
        body: bytes,
      );

      if (res.statusCode == 200) {
        _fetchNotes();
      }
    }
  }

  // --- RECAPPER ESP32 TRIGGER LOGIC ---
  Future<void> _triggerRecapperCapture(bool start) async {
    try {
      final action = start ? "start" : "stop";
      await http.post(Uri.parse('$recapperIp/api/record?action=$action'));
      setState(() => _isRecording = start);
      if (!start) {
        // Allow time for ESP32 to push payload to Cloudflare, then refresh
        Future.delayed(const Duration(seconds: 2), _fetchNotes);
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not reach Recapper hardware device.')),
      );
    }
  }

  void _openAISearchSheet() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => AISearchSheet(baseUrl: cloudflareWorkerUrl),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Recap'),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _fetchNotes,
          )
        ],
      ),
      body: Column(
        children: [
          // Hardware / Phone Toggle Card
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
            child: Card.outlined(
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: Row(
                  children: [
                    Icon(
                      _useRecapperHardware ? Icons.hardware : Icons.phone_android,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _useRecapperHardware ? 'Connected to Recapper' : 'Using Phone Mic',
                            style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          Text(
                            _useRecapperHardware 
                                ? 'Recording streams through ESP32 + INMP441' 
                                : 'Recording directly via device hardware',
                            style: theme.textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: _useRecapperHardware,
                      onChanged: (val) {
                        setState(() => _useRecapperHardware = val);
                      },
                    )
                  ],
                ),
              ),
            ),
          ),

          // Central Active Recording Card
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Card(
              elevation: 0,
              color: _isRecording ? theme.colorScheme.errorContainer : theme.colorScheme.surfaceContainerHigh,
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: Column(
                  children: [
                    Icon(
                      _isRecording ? Icons.graphic_eq : Icons.mic_none,
                      size: 48,
                      color: _isRecording ? theme.colorScheme.onErrorContainer : theme.colorScheme.primary,
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _isRecording ? 'Listening and transcribing...' : 'Press and hold to record note',
                      style: theme.textTheme.titleSmall?.copyWith(
                        color: _isRecording ? theme.colorScheme.onErrorContainer : theme.colorScheme.onSurface,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Transcribed Feed
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _notes.isEmpty
                    ? Center(child: Text('No transcripts captured yet.', style: theme.textTheme.bodyLarge))
                    : ListView.builder(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        itemCount: _notes.length,
                        itemBuilder: (context, index) {
                          final note = _notes[index];
                          final dateStr = DateFormat('MMM d, h:mm a').format(
                            DateTime.fromMillisecondsSinceEpoch((note['timestamp'] ?? 0) * 1000),
                          );
                          final isRecapper = note['mode']?.contains('recapper') ?? false;

                          return Card.outlined(
                            margin: const EdgeInsets.only(bottom: 12),
                            child: ListTile(
                              leading: CircleAvatar(
                                backgroundColor: isRecapper 
                                    ? theme.colorScheme.tertiaryContainer 
                                    : theme.colorScheme.primaryContainer,
                                child: Icon(
                                  isRecapper ? Icons.memory : Icons.mic,
                                  size: 20,
                                  color: isRecapper 
                                      ? theme.colorScheme.onTertiaryContainer 
                                      : theme.colorScheme.onPrimaryContainer,
                                ),
                              ),
                              title: Text(note['text'] ?? ''),
                              subtitle: Padding(
                                padding: const EdgeInsets.only(top: 4.0),
                                child: Text('$dateStr • Source: ${note['mode']}'),
                              ),
                            ),
                          );
                        },
                      ),
          ),
        ],
      ),

      // Press-and-Hold Record Action
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
      floatingActionButton: GestureDetector(
        onLongPressStart: (_) {
          if (_useRecapperHardware) {
            _triggerRecapperCapture(true);
          } else {
            _startPhoneRecording();
          }
        },
        onLongPressEnd: (_) {
          if (_useRecapperHardware) {
            _triggerRecapperCapture(false);
          } else {
            _stopPhoneRecordingAndSend();
          }
        },
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            FloatingActionButton.large(
              onPressed: () {},
              child: Icon(_isRecording ? Icons.mic_fixed : Icons.mic),
            ),
            const SizedBox(width: 16),
            FloatingActionButton(
              onPressed: _openAISearchSheet,
              child: const Icon(Icons.auto_awesome),
            )
          ],
        ),
      ),
    );
  }
}

class AISearchSheet extends StatefulWidget {
  final String baseUrl;
  const AISearchSheet({super.key, required this.baseUrl});

  @override
  State<AISearchSheet> createState() => _AISearchSheetState();
}

class _AISearchSheetState extends State<AISearchSheet> {
  final TextEditingController _queryController = TextEditingController();
  bool _isSearching = false;
  String? _aiAnswer;
  List<dynamic> _matches = [];

  Future<void> _search() async {
    final q = _queryController.text.trim();
    if (q.isEmpty) return;

    setState(() {
      _isSearching = true;
      _aiAnswer = null;
    });

    try {
      final res = await http.get(Uri.parse('${widget.baseUrl}/api/search?q=${Uri.encodeComponent(q)}'));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body);
        setState(() {
          _aiAnswer = data['ai_answer'];
          _matches = data['matched_notes'] ?? [];
          _isSearching = false;
        });
      }
    } catch (_) {
      setState(() => _isSearching = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
        left: 16, right: 16, top: 24,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.auto_awesome, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text('Recap AI Memory Search', style: theme.textTheme.titleLarge),
              ],
            ),
            const SizedBox(height: 16),
            SearchBar(
              controller: _queryController,
              hintText: 'Search your transcripts with AI...',
              trailing: [IconButton(icon: const Icon(Icons.search), onPressed: _search)],
              onSubmitted: (_) => _search(),
            ),
            const SizedBox(height: 20),
            if (_isSearching) const Center(child: CircularProgressIndicator()),
            if (_aiAnswer != null) ...[
              Card(
                color: theme.colorScheme.primaryContainer,
                elevation: 0,
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Text(
                    _aiAnswer!,
                    style: TextStyle(color: theme.colorScheme.onPrimaryContainer),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Text('Context Snippets:', style: theme.textTheme.titleSmall),
              ..._matches.map((m) => ListTile(
                dense: true,
                title: Text(m['text'] ?? ''),
                subtitle: Text(m['mode'] ?? ''),
              )),
            ],
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}
