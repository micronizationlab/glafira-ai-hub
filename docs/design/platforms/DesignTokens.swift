// Reference implementation for the future GLAFIRA iOS target.
// This file is intentionally outside an iOS build target until that target exists.
import SwiftUI
import UIKit

enum GlafiraDesignTokens {
    enum Palette {
        static let brandPrimary = SwiftUI.Color(glafiraLight: 0xFF78B83A, glafiraDark: 0xFF9AD45A)
        static let brandAction = SwiftUI.Color(glafiraLight: 0xFF3E6F20, glafiraDark: 0xFF9AD45A)
        static let brandOnAction = SwiftUI.Color(glafiraLight: 0xFFFFFFFF, glafiraDark: 0xFF14220A)
        static let surfaceCanvas = SwiftUI.Color(glafiraLight: 0xFFF3F6F0, glafiraDark: 0xFF151A17)
        static let surfacePanel = SwiftUI.Color(glafiraLight: 0xFFFFFFFF, glafiraDark: 0xFF202722)
        static let surfaceRaised = SwiftUI.Color(glafiraLight: 0xFFFFFFFF, glafiraDark: 0xFF29332C)
        static let textPrimary = SwiftUI.Color(glafiraLight: 0xFF182019, glafiraDark: 0xFFF1F5F0)
        static let textSecondary = SwiftUI.Color(glafiraLight: 0xFF59645B, glafiraDark: 0xFFB5C0B6)
        static let controlOutline = SwiftUI.Color(glafiraLight: 0xFF657365, glafiraDark: 0xFF7E8D80)
        static let focusRing = SwiftUI.Color(glafiraLight: 0xFF245EAA, glafiraDark: 0xFF8DBDFF)
        static let statusNeutral = SwiftUI.Color(glafiraLight: 0xFF66736A, glafiraDark: 0xFFA5B0A7)
        static let statusSuccess = SwiftUI.Color(glafiraLight: 0xFF238343, glafiraDark: 0xFF62C77E)
        static let statusWarning = SwiftUI.Color(glafiraLight: 0xFFA96708, glafiraDark: 0xFFF0B34D)
        static let statusDanger = SwiftUI.Color(glafiraLight: 0xFFB4232C, glafiraDark: 0xFFFF747C)
        static let statusInfo = SwiftUI.Color(glafiraLight: 0xFF2868B2, glafiraDark: 0xFF79B6F5)
    }

    enum Spacing {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
    }

    enum CornerRadius {
        static let control: CGFloat = 10
        static let card: CGFloat = 14
        static let panel: CGFloat = 18
        static let dialog: CGFloat = 20
    }

    static let minimumTouchTarget: CGFloat = 44
}

private extension SwiftUI.Color {
    init(glafiraLight lightHex: UInt, glafiraDark darkHex: UInt) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? darkHex : lightHex
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: CGFloat((hex >> 24) & 0xFF) / 255
            )
        })
    }
}
