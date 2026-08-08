package xyz.alumos.lumosreader

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val LumosShape = RoundedCornerShape(12.dp)

@Composable
fun LumosTheme(eink: Boolean, dark: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        eink -> lightColorScheme(
            primary = Color.Black, onPrimary = Color.White,
            primaryContainer = Color.White, onPrimaryContainer = Color.Black,
            secondary = Color.White, onSecondary = Color.Black,
            secondaryContainer = Color.White, onSecondaryContainer = Color.Black,
            tertiary = Color.White, onTertiary = Color.Black,
            background = Color.White, onBackground = Color.Black,
            surface = Color.White, onSurface = Color.Black,
            surfaceVariant = Color.White, onSurfaceVariant = Color.Black,
            surfaceDim = Color.White, surfaceBright = Color.White,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
            surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White,
            inverseSurface = Color.Black, inverseOnSurface = Color.White,
            error = Color.Black, onError = Color.White,
            errorContainer = Color.White, onErrorContainer = Color.Black,
            outline = Color.Black, outlineVariant = Color.Black,
            scrim = Color.Black,
        )
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val shapes = Shapes(
        extraSmall = LumosShape, small = LumosShape, medium = LumosShape,
        large = LumosShape, extraLarge = LumosShape,
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), shapes = shapes, content = content)
}

@Composable
fun lumosBorder(eink: Boolean): BorderStroke? = if (eink) BorderStroke(1.dp, Color.Black) else null

@Composable
fun lumosButtonColors(eink: Boolean): ButtonColors = if (eink) {
    ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
        disabledContainerColor = Color.White,
        disabledContentColor = Color.Black,
    )
} else ButtonDefaults.buttonColors()

@Composable
fun lumosOutlinedButtonColors(eink: Boolean): ButtonColors = if (eink) {
    ButtonDefaults.outlinedButtonColors(
        contentColor = Color.Black,
        disabledContentColor = Color.Black,
    )
} else ButtonDefaults.outlinedButtonColors()

@Composable
fun lumosFilterChipColors(eink: Boolean) = if (eink) {
    FilterChipDefaults.filterChipColors(
        containerColor = Color.White,
        labelColor = Color.Black,
        iconColor = Color.Black,
        disabledContainerColor = Color.White,
        disabledLabelColor = Color.Black,
        disabledLeadingIconColor = Color.Black,
        disabledTrailingIconColor = Color.Black,
        selectedContainerColor = Color.White,
        selectedLabelColor = Color.Black,
        selectedLeadingIconColor = Color.Black,
        selectedTrailingIconColor = Color.Black,
    )
} else FilterChipDefaults.filterChipColors()
