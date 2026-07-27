package saien.someday.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared visual language for Someday's stable content layer.
 *
 * Content uses quiet, opaque materials and a continuous corner-radius family;
 * floating navigation and controls use the separate Liquid Glass primitives.
 */
object SomedayDesignDefaults {
    val ContentCardShape = RoundedCornerShape(18.dp)
    val SectionShape = RoundedCornerShape(20.dp)
    val IconShape = RoundedCornerShape(14.dp)
    val CellShape = RoundedCornerShape(14.dp)
    val ToastShape = RoundedCornerShape(20.dp)
    val PillShape = RoundedCornerShape(50)

    val PageHorizontalPadding = 24.dp
    val CompactPageHorizontalPadding = 20.dp
    val SectionSpacing = 24.dp

    val MaterialShapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = ContentCardShape,
        large = SectionShape,
        extraLarge = RoundedCornerShape(28.dp),
    )

    private val baseTypography = Typography()

    val MaterialTypography = Typography(
        headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = baseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = baseTypography.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}
