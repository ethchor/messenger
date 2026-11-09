import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The parts of a settings page, as designed: the grid ground, a glass
/// header with "‹ You" and a centred title, and white cards with a hairline.
enum Kit {
    /// The hairline around cards.
    static let cardLine = Color(light: 0xE4E0D4, dark: 0x2A2A2F)
    /// Fields and the theme switcher's track.
    static let field = Color(light: 0xFFFFFF, dark: 0x1F1F23)
    /// A segmented control's track and thumb.
    static let segTrack = Color(light: 0xE9E6DC, dark: 0x2A2A2F)
    static let segThumb = Color(light: 0xFFFFFF, dark: 0x4A4A52)
    /// "On" dots.
    static let okGreen = Color(rgb: 0x2E9E5B)
}

/// A settings page: opened from You, with "‹ You" and its title on top.
struct DesignPage<Content: View>: View {
    let title: String
    var back = "You"
    @ViewBuilder var content: Content
    @Environment(\.dismiss) private var dismiss

    init(_ title: String, back: String = "You", @ViewBuilder content: () -> Content) {
        self.title = title
        self.back = back
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) { content }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 40)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
        }
        .background { HomeGround().ignoresSafeArea() }
        #if os(iOS)
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .background(PopGesture())
        #else
        .navigationTitle(title)
        #endif
    }

    private var header: some View {
        ZStack {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .padding(.horizontal, 84)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Button { dismiss() } label: {
                    HStack(spacing: 0) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 19, weight: .semibold))
                        Text(back).font(.system(size: 16))
                    }
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, 6)
                    .frame(height: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back to \(back)")
                Spacer()
            }
        }
        .padding(.leading, 4)
        .padding(.trailing, 8)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .background(.ultraThinMaterial)
    }
}

#if os(iOS)
/// Keeps the edge swipe back working on pages that draw their own header.
private struct PopGesture: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let nav = navigationController, let pop = nav.interactivePopGestureRecognizer else { return }
            let delegate = PopDelegate.shared
            delegate.nav = nav
            pop.delegate = delegate
            pop.isEnabled = true
        }
    }

    final class PopDelegate: NSObject, UIGestureRecognizerDelegate {
        static let shared = PopDelegate()
        weak var nav: UINavigationController?
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (nav?.viewControllers.count ?? 0) > 1
        }
    }
}
#endif

/// A small grey label over a card.
struct KitLabel: View {
    let text: String
    var trailing: String?

    init(_ text: String, trailing: String? = nil) {
        self.text = text
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(text)
            Spacer(minLength: 8)
            if let trailing { Text(trailing) }
        }
        .font(.system(size: 13, weight: .semibold))
        .foregroundStyle(Palette.secondary)
        .padding(.horizontal, 4)
        .padding(.top, 10)
        .accessibilityAddTraits(.isHeader)
    }
}

/// The small print under a card.
struct KitFoot: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .lineSpacing(2)
            .foregroundStyle(Palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 6)
            .padding(.top, 2)
            .padding(.bottom, 6)
    }
}

/// A white card with a hairline: rows, or anything.
struct KitCard<Content: View>: View {
    var radius: CGFloat = 20
    var padding: CGFloat = 0
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Kit.cardLine, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// The hairline between rows.
struct KitSeparator: View {
    var body: some View {
        Rectangle().fill(Palette.line).frame(height: 1)
    }
}

/// A row's label and the line under it.
private struct KitRowLabel: View {
    let label: String
    var hint: String?
    var color: Color = Palette.text

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: 16)).foregroundStyle(color)
            if let hint {
                Text(hint)
                    .font(.system(size: 13))
                    .lineSpacing(1)
                    .foregroundStyle(Palette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A switch row.
struct KitToggle: View {
    let label: String
    var hint: String?
    @Binding var isOn: Bool

    init(_ label: String, hint: String? = nil, isOn: Binding<Bool>) {
        self.label = label
        self.hint = hint
        self._isOn = isOn
    }

    var body: some View {
        Toggle(isOn: $isOn) { KitRowLabel(label: label, hint: hint) }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 54)
    }
}

/// A row with a value on the right.
struct KitValue<Value: View>: View {
    let label: String
    var hint: String?
    @ViewBuilder var value: Value

    var body: some View {
        HStack(spacing: 12) {
            KitRowLabel(label: label, hint: hint)
            value
                .font(.system(size: 14.5))
                .foregroundStyle(Palette.secondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(minHeight: 54)
        .accessibilityElement(children: .combine)
    }
}

extension KitValue where Value == Text {
    init(_ label: String, value: String, hint: String? = nil) {
        self.label = label
        self.hint = hint
        self.value = Text(value)
    }
}

/// A row that opens a page.
struct KitLink<Destination: View>: View {
    let label: String
    var hint: String?
    var value: String?
    @ViewBuilder var destination: () -> Destination

    var body: some View {
        NavigationLink(destination: destination) {
            HStack(spacing: 10) {
                KitRowLabel(label: label, hint: hint)
                if let value {
                    Text(value).font(.system(size: 14.5)).foregroundStyle(Palette.secondary)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.secondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A row that does something: left-aligned, or centred for the last card.
struct KitButton: View {
    let label: String
    var color: Color = Palette.text
    var weight: Font.Weight = .regular
    var centred = false
    var height: CGFloat = 54
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 16, weight: weight))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity, alignment: centred ? .center : .leading)
                .padding(.horizontal, 14)
                .frame(minHeight: height)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A segmented control with a springy thumb: Theme, and Sticker / Photo / Initials.
struct KitSegmented: View {
    let labels: [String]
    @Binding var selection: Int
    /// Theme's big one: 40 pt on a field with a hairline. Otherwise 34 pt, iOS-like.
    var large = false
    @Namespace private var thumb

    var body: some View {
        let inset: CGFloat = large ? 4 : 2
        let radius: CGFloat = large ? 16 : 10
        HStack(spacing: 0) {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                Button {
                    withAnimation(.spring(response: 0.38, dampingFraction: 0.68)) { selection = i }
                } label: {
                    Text(label)
                        .font(.system(size: large ? 14.5 : 14, weight: large || i == selection ? .semibold : .medium))
                        .foregroundStyle(large && i != selection ? Palette.secondary : Palette.text)
                        .frame(maxWidth: .infinity)
                        .frame(height: large ? 40 : 34)
                        .background {
                            if i == selection {
                                RoundedRectangle(cornerRadius: radius - inset, style: .continuous)
                                    .fill(large ? Palette.surface : Kit.segThumb)
                                    .shadow(color: .black.opacity(0.12), radius: large ? 2 : 4, y: large ? 1 : 3)
                                    .matchedGeometryEffect(id: "thumb", in: thumb)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(i == selection ? .isSelected : [])
            }
        }
        .padding(inset)
        .background(large ? Kit.field : Kit.segTrack, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay {
            if large {
                RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Palette.line, lineWidth: 1)
            }
        }
    }
}

/// The nine inks in one row; the chosen one ringed.
struct InkSwatches: View {
    @Binding var ink: Ink

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: Ink.allCases.count), spacing: 6) {
            ForEach(Ink.allCases) { option in
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { ink = option }
                } label: {
                    Circle()
                        .fill(option.bubble)
                        .aspectRatio(1, contentMode: .fit)
                        .padding(5)
                        .overlay {
                            if ink == option {
                                Circle().strokeBorder(option.bubble, lineWidth: 2)
                            }
                        }
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(option.name)
                .accessibilityAddTraits(ink == option ? .isSelected : [])
            }
        }
    }
}

/// Chips that wrap onto more lines.
struct FlowRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = arrange(width: bounds.width, subviews: subviews)
        for row in rows {
            var x = bounds.minX
            for index in row.items {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row { var items: [Int] = []; var y: CGFloat = 0; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if !rows[rows.count - 1].items.isEmpty && rows[rows.count - 1].width + spacing + size.width > width {
                let last = rows[rows.count - 1]
                rows.append(Row(y: last.y + last.height + spacing))
            }
            var row = rows[rows.count - 1]
            row.width += (row.items.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.items.append(index)
            rows[rows.count - 1] = row
        }
        return rows
    }
}

/// A paper's chip: filled when chosen, with LIVE on the ones that move.
struct PaperChip: View {
    let paper: Paper
    let selected: Bool
    let action: () -> Void

    private var moves: Bool { if case .live = paper { true } else { false } }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(paper.name).font(.system(size: 14, weight: .medium))
                if moves {
                    Text("LIVE")
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundStyle(Color(rgb: 0x17171A))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Palette.marigold, in: Capsule())
                }
            }
            .foregroundStyle(selected ? Palette.ground : Palette.text)
            .padding(.horizontal, 14)
            .frame(height: 38)
            .background(Capsule().fill(selected ? Palette.text : Color.clear))
            .overlay(Capsule().strokeBorder(selected ? Palette.text : Palette.line, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(paper.name + (moves ? ", moves" : ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A small status pill: a coloured dot and a word.
struct KitPill: View {
    let text: String
    var dot: Color = Kit.okGreen

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(dot).frame(width: 7, height: 7)
            Text(text).font(.system(size: 13, weight: .semibold))
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 10)
        .frame(height: 26)
        .background(Palette.ground, in: Capsule())
        .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1))
    }
}

extension View {
    /// Text fields drawn by our own shapes: no system box or focus ring
    /// inside them (the Mac draws both by default).
    func designFields() -> some View {
        textFieldStyle(.plain).focusEffectDisabled()
    }
}
