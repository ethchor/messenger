import Observation
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

// The look of the app: inks to colour it, papers to put behind chats, and
// drawn avatars (see AvatarArt.swift). The inks are deep, like fountain pen
// ink, so white text reads well on the bubbles you send.

extension Color {
    /// A colour with a separate value in dark mode.
    init(light: UInt32, dark: UInt32, lightOpacity: Double = 1, darkOpacity: Double = 1) {
        #if os(iOS)
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(rgb: dark, alpha: darkOpacity)
                : UIColor(rgb: light, alpha: lightOpacity)
        })
        #elseif os(macOS)
        self.init(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                ? NSColor(rgb: dark, alpha: darkOpacity)
                : NSColor(rgb: light, alpha: lightOpacity)
        })
        #endif
    }

    /// Bubbles from others. Opaque, so chat paper doesn't show through text.
    static let incomingBubble = Color(light: 0xEDEFF3, dark: 0x2B2E34)
}

#if os(iOS)
private extension UIColor {
    convenience init(rgb: UInt32, alpha: Double) {
        self.init(
            red: CGFloat(rgb >> 16 & 0xFF) / 255,
            green: CGFloat(rgb >> 8 & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
#elseif os(macOS)
private extension NSColor {
    convenience init(rgb: UInt32, alpha: Double) {
        self.init(
            srgbRed: CGFloat(rgb >> 16 & 0xFF) / 255,
            green: CGFloat(rgb >> 8 & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
#endif

/// The colours to choose from. Yours tints the app, and others see your
/// avatar in it.
enum Ink: String, CaseIterable, Identifiable {
    case blueBlack = "blue-black"
    case teal
    case forest
    case oxblood
    case aubergine
    case saffron
    case sepia
    case graphite

    var id: String { rawValue }

    var name: String {
        switch self {
        case .blueBlack: "Blue-black"
        case .teal: "Teal"
        case .forest: "Forest"
        case .oxblood: "Oxblood"
        case .aubergine: "Aubergine"
        case .saffron: "Saffron"
        case .sepia: "Sepia"
        case .graphite: "Graphite"
        }
    }

    /// (light, dark, dark-mode bubble). Each passes 5:1 or better for text.
    private var values: (UInt32, UInt32, UInt32) {
        switch self {
        case .blueBlack: (0x26457E, 0x9BB4E6, 0x2E4F8A)
        case .teal: (0x0B6E6E, 0x6CCFCB, 0x0F6767)
        case .forest: (0x2D6A39, 0x8ACF97, 0x2B6436)
        case .oxblood: (0x872235, 0xEE9AAA, 0x7E2434)
        case .aubergine: (0x5C3886, 0xC3A6EC, 0x583882)
        case .saffron: (0x8A5A00, 0xEDC15A, 0x7A5100)
        case .sepia: (0x6A4A2D, 0xD9B58F, 0x684A2E)
        case .graphite: (0x3E4550, 0xC5CBD4, 0x3D444E)
        }
    }

    /// Lines, buttons and links.
    var color: Color { Color(light: values.0, dark: values.1) }

    /// The bubbles you send, with white text on them.
    var bubble: Color { Color(light: values.0, dark: values.2) }

    /// A pale wash, behind selected things.
    var wash: Color { Color(light: values.0, dark: values.1, lightOpacity: 0.12, darkOpacity: 0.2) }

    /// The ink at full depth in either mode: initials on the pastel.
    var deep: Color { Color(rgb: values.0) }

    /// The pale circle behind someone's avatar. The same in dark mode, like
    /// a photo would be.
    var pastel: Color {
        switch self {
        case .blueBlack: Color(rgb: 0xDCE6FA)
        case .teal: Color(rgb: 0xD2F0EC)
        case .forest: Color(rgb: 0xDCF1DA)
        case .oxblood: Color(rgb: 0xF9DDE3)
        case .aubergine: Color(rgb: 0xEADFF8)
        case .saffron: Color(rgb: 0xFCEFC6)
        case .sepia: Color(rgb: 0xF3E6D5)
        case .graphite: Color(rgb: 0xE3E6EB)
        }
    }

    /// The ink named in someone's profile, if it's one we know.
    init?(named name: String?) {
        guard let name, let ink = Ink(rawValue: name) else { return nil }
        self = ink
    }

    /// A steady ink for someone who hasn't picked one.
    static func forName(_ name: String) -> Ink {
        // Stable across launches, unlike hashValue.
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return allCases[sum % allCases.count]
    }
}

extension AppModel {
    /// Someone's ink: the one they picked, or a steady one from their name.
    func ink(for username: String) -> Ink {
        Ink(named: profiles[username]?.ink) ?? Ink.forName(username)
    }
}

/// What goes behind a chat, drawn in the ink.
enum Paper: String, CaseIterable, Identifiable {
    case plain
    case dots
    case ruled
    case grid
    case doodles
    case wash

    var id: String { rawValue }

    var name: String {
        switch self {
        case .plain: "Plain"
        case .dots: "Dots"
        case .ruled: "Ruled"
        case .grid: "Grid"
        case .doodles: "Doodles"
        case .wash: "Ink wash"
        }
    }
}

/// This device's choices: the ink, the paper behind chats, and papers picked
/// for single chats. Kept in UserDefaults; the ink is also shared, through
/// the profile.
@MainActor
@Observable
final class Appearance {
    private static let inkKey = "appearance.ink"
    private static let paperKey = "appearance.paper"
    private static let chatPapersKey = "appearance.chatPapers"

    var ink: Ink {
        didSet { UserDefaults.standard.set(ink.rawValue, forKey: Self.inkKey) }
    }

    var paper: Paper {
        didSet { UserDefaults.standard.set(paper.rawValue, forKey: Self.paperKey) }
    }

    private(set) var chatPapers: [String: Paper]

    init() {
        let defaults = UserDefaults.standard
        ink = Ink(named: defaults.string(forKey: Self.inkKey)) ?? .blueBlack
        paper = defaults.string(forKey: Self.paperKey).flatMap(Paper.init(rawValue:)) ?? .plain
        let stored = defaults.dictionary(forKey: Self.chatPapersKey) as? [String: String] ?? [:]
        chatPapers = stored.compactMapValues(Paper.init(rawValue:))
    }

    func paper(for conversationId: String) -> Paper {
        chatPapers[conversationId] ?? paper
    }

    /// A paper for one chat, or nil to follow the default.
    func setPaper(_ paper: Paper?, for conversationId: String) {
        chatPapers[conversationId] = paper
        UserDefaults.standard.set(chatPapers.mapValues(\.rawValue), forKey: Self.chatPapersKey)
    }

    /// Back to the start, when the account leaves this device.
    func reset() {
        for key in [Self.inkKey, Self.paperKey, Self.chatPapersKey] {
            UserDefaults.standard.removeObject(forKey: key)
        }
        ink = .blueBlack
        paper = .plain
        chatPapers = [:]
    }
}

/// A chat's paper, filling the space it's given.
struct PaperView: View {
    let paper: Paper
    let ink: Ink
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let strength = colorScheme == .dark ? 0.22 : 0.16
        ZStack {
            Color.messengerBackground
            switch paper {
            case .plain:
                EmptyView()
            case .wash:
                ink.color.opacity(colorScheme == .dark ? 0.12 : 0.07)
            case .dots, .ruled, .grid, .doodles:
                Canvas { context, size in
                    draw(paper, in: &context, size: size)
                }
                .foregroundStyle(ink.color.opacity(strength))
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ paper: Paper, in context: inout GraphicsContext, size: CGSize) {
        switch paper {
        case .dots:
            let gap: CGFloat = 18
            var dots = Path()
            for x in stride(from: gap / 2, to: size.width, by: gap) {
                for y in stride(from: gap / 2, to: size.height, by: gap) {
                    dots.addEllipse(in: CGRect(x: x - 1.1, y: y - 1.1, width: 2.2, height: 2.2))
                }
            }
            context.fill(dots, with: .foreground)
        case .ruled:
            var lines = Path()
            for y in stride(from: 28, to: size.height, by: 28) {
                lines.move(to: CGPoint(x: 0, y: y))
                lines.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(lines, with: .foreground, lineWidth: 0.75)
        case .grid:
            var lines = Path()
            for x in stride(from: 0, to: size.width, by: 22) {
                lines.move(to: CGPoint(x: x, y: 0))
                lines.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in stride(from: 0, to: size.height, by: 22) {
                lines.move(to: CGPoint(x: 0, y: y))
                lines.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(lines, with: .foreground, lineWidth: 0.5)
        case .doodles:
            let tile: CGFloat = 120
            let art = Artwork.paperDoodles
            let scale = tile / art.size.width
            let strokes = ArtworkCache.strokes(for: art)
            for x in stride(from: 0, to: size.width, by: tile) {
                for y in stride(from: 0, to: size.height, by: tile) {
                    let transform = CGAffineTransform(translationX: x, y: y).scaledBy(x: scale, y: scale)
                    for (stroke, path) in strokes {
                        context.stroke(
                            path.applying(transform),
                            with: .foreground,
                            style: StrokeStyle(lineWidth: stroke.width * scale, lineCap: .round, lineJoin: .round)
                        )
                    }
                }
            }
        case .plain, .wash:
            break
        }
    }
}
