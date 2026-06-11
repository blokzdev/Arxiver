package dev.blokz.arxiver.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// Paper titles use a serif accent (SPEC-UI §1); everything else is the default sans scale.
private val Serif = FontFamily.Serif

val ArxiverTypography =
    Typography().run {
        copy(
            headlineSmall = headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
            titleMedium =
                titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    lineHeightStyle =
                        LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None,
                        ),
                ),
        )
    }
