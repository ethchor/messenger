import AVFoundation
import Observation

/// A voice note just recorded.
struct VoiceNote {
    let url: URL
    let duration: TimeInterval
    /// Loudness over time, 0 to 255, at most 256 steps.
    let waveform: [UInt8]
}

/// Records voice notes while the button is held: AAC in an .m4a, with the
/// loudness sampled every 50 ms for the waveform the others see.
@MainActor
@Observable
final class VoiceRecorder {
    private(set) var isRecording = false
    private(set) var elapsed: TimeInterval = 0
    /// Loudness so far, 0 to 1.
    private(set) var levels: [Float] = []

    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var timer: Timer?

    /// Anything shorter was a tap on the button, not a message.
    static let shortest: TimeInterval = 1

    enum Access { case allowed, denied, notAsked }

    static var access: Access {
        #if os(iOS)
        switch AVAudioApplication.shared.recordPermission {
        case .granted: .allowed
        case .denied: .denied
        default: .notAsked
        }
        #else
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: .allowed
        case .notDetermined: .notAsked
        default: .denied
        }
        #endif
    }

    static func requestAccess() async -> Bool {
        #if os(iOS)
        await AVAudioApplication.requestRecordPermission()
        #else
        await AVCaptureDevice.requestAccess(for: .audio)
        #endif
    }

    func start() throws {
        guard !isRecording else { return }
        VoicePlayer.shared.stop()
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        #endif
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 48_000,
        ]
        let recorder = try AVAudioRecorder(url: AppFiles.outgoingFile(extension: "m4a"), settings: settings)
        recorder.isMeteringEnabled = true
        guard recorder.record() else {
            recorder.deleteRecording()
            throw RecordingError.unavailable
        }
        self.recorder = recorder
        levels = []
        elapsed = 0
        isRecording = true
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.sample() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func sample() {
        guard let recorder, recorder.isRecording else { return }
        recorder.updateMeters()
        // Speech sits between about -40 dB and 0.
        let power = recorder.averagePower(forChannel: 0)
        levels.append(max(0, min(1, (power + 50) / 50)))
        elapsed = recorder.currentTime
    }

    /// Stops, and returns the note unless it was too short to be one.
    func finish() -> VoiceNote? {
        guard let recorder else { return nil }
        let duration = recorder.currentTime
        stop()
        guard duration >= Self.shortest else {
            recorder.deleteRecording()
            return nil
        }
        return VoiceNote(url: recorder.url, duration: duration, waveform: Self.waveform(levels))
    }

    /// Stops and throws the recording away.
    func cancel() {
        guard let recorder else { return }
        stop()
        recorder.deleteRecording()
    }

    private func stop() {
        timer?.invalidate()
        timer = nil
        recorder?.stop()
        recorder = nil
        isRecording = false
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    /// At most 256 steps, each the loudest moment of its stretch.
    static func waveform(_ levels: [Float]) -> [UInt8] {
        let steps = min(MediaLimits.waveformSteps, levels.count)
        guard steps > 0 else { return [] }
        return (0..<steps).map { step in
            let start = step * levels.count / steps
            let end = max(start + 1, (step + 1) * levels.count / steps)
            let loudest = levels[start..<end].max() ?? 0
            return UInt8((loudest * 255).rounded())
        }
    }
}

enum RecordingError: LocalizedError {
    case unavailable

    var errorDescription: String? {
        "The microphone can't be used right now."
    }
}

/// Plays voice notes, one at a time.
@MainActor
@Observable
final class VoicePlayer: NSObject {
    static let shared = VoicePlayer()

    /// The note playing or paused.
    private(set) var path: String?
    private(set) var isPlaying = false
    private(set) var currentTime: TimeInterval = 0
    private(set) var duration: TimeInterval = 0

    var progress: Double {
        duration > 0 ? min(1, currentTime / duration) : 0
    }

    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var timer: Timer?

    /// Plays the note, or pauses it if it's playing.
    func toggle(_ path: String) {
        if self.path == path, let player {
            if player.isPlaying {
                player.pause()
                isPlaying = false
                stopTimer()
            } else {
                player.play()
                isPlaying = true
                startTimer()
            }
            return
        }
        stop()
        do {
            #if os(iOS)
            // Out loud, even with the ring switch on silent, as people expect.
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            let player = try AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
            player.delegate = self
            guard player.play() else { return }
            self.player = player
            self.path = path
            duration = player.duration
            currentTime = 0
            isPlaying = true
            startTimer()
        } catch {
            stop()
        }
    }

    /// Jumps within the note that's playing or paused.
    func seek(_ path: String, to fraction: Double) {
        guard self.path == path, let player else { return }
        player.currentTime = max(0, min(1, fraction)) * player.duration
        currentTime = player.currentTime
    }

    func stop() {
        stopTimer()
        let wasActive = player != nil
        player?.stop()
        player = nil
        path = nil
        isPlaying = false
        currentTime = 0
        duration = 0
        #if os(iOS)
        if wasActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        #endif
    }

    private func startTimer() {
        stopTimer()
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let player = self.player else { return }
                self.currentTime = player.currentTime
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func finished(_ id: ObjectIdentifier) {
        guard let player, ObjectIdentifier(player) == id else { return }
        stop()
    }
}

extension VoicePlayer: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let id = ObjectIdentifier(player)
        Task { @MainActor in self.finished(id) }
    }
}
