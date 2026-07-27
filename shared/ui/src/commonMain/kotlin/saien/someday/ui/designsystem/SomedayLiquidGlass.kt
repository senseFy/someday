package saien.someday.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/** Material emphasis for Someday controls that float above app content. */
enum class SomedayLiquidGlassStyle {
    Regular,
    Prominent,
    Clear,
}

/** Stable geometry and optical starting points shared by Someday glass controls. */
object SomedayLiquidGlassDefaults {
    val ButtonHeight = 48.dp
    val IconButtonSize = 46.dp
    val FloatingActionButtonSize = 56.dp
    val ActionGroupHeight = 46.dp
    val BottomBarHeight = 64.dp
    val ContentPadding = 16.dp
    val ContentSpacing = 8.dp
    val BlurRadius = 2.dp
    val RefractionHeight = 12.dp
    val RefractionAmount = 24.dp
}

private val LiquidCapsuleShape = RoundedCornerShape(50)
private val LocalSomedayLiquidGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Owns a backdrop for a screen region. Place [SomedayLiquidGlassBackdrop] and glass
 * controls as siblings inside this host so controls never sample themselves.
 */
@Composable
fun SomedayLiquidGlassHost(
    content: @Composable () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(
        LocalSomedayLiquidGlassBackdrop provides backdrop,
        content = content,
    )
}

/**
 * Records [backgroundColor] and content that glass siblings can sample. Nested glass
 * controls deliberately receive no backdrop here, preventing recursive rendering;
 * use another host when a nested toolbar needs its own source.
 */
@Composable
fun SomedayLiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalSomedayLiquidGlassBackdrop.current
    val sourceModifier = if (backdrop == null) {
        modifier.background(backgroundColor)
    } else {
        modifier
            .layerBackdrop(backdrop)
            .background(backgroundColor)
    }
    Box(sourceModifier) {
        CompositionLocalProvider(LocalSomedayLiquidGlassBackdrop provides null) {
            content()
        }
    }
}

/** Base non-clickable glass surface for custom controls and navigation containers. */
@Composable
fun SomedayLiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = LiquidCapsuleShape,
    style: SomedayLiquidGlassStyle = SomedayLiquidGlassStyle.Regular,
    enabled: Boolean = true,
    contentColor: Color = somedayLiquidGlassContentColor(style, enabled),
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier.somedayLiquidGlass(
                shape = shape,
                style = style,
                enabled = enabled,
            ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** Standard 48dp capsule button with Someday's Liquid Glass material. */
@Composable
fun SomedayLiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: SomedayLiquidGlassStyle = SomedayLiquidGlassStyle.Regular,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "Someday liquid button press",
    )
    val contentColor = somedayLiquidGlassContentColor(style, enabled)

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                SomedayLiquidGlassDefaults.ContentSpacing,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .somedayLiquidGlass(
                    shape = LiquidCapsuleShape,
                    style = style,
                    enabled = enabled,
                    scale = pressedScale,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .height(SomedayLiquidGlassDefaults.ButtonHeight)
                .padding(horizontal = SomedayLiquidGlassDefaults.ContentPadding),
            content = content,
        )
    }
}

/** Circular icon-only button with a 46dp default hit target. */
@Composable
fun SomedayLiquidGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: SomedayLiquidGlassStyle = SomedayLiquidGlassStyle.Regular,
    size: Dp = SomedayLiquidGlassDefaults.IconButtonSize,
    iconSize: Dp = 20.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "Someday liquid icon button press",
    )
    val contentColor = somedayLiquidGlassContentColor(style, enabled)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .somedayLiquidGlass(
                shape = CircleShape,
                style = style,
                enabled = enabled,
                scale = pressedScale,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** A single glass capsule containing related icon actions. */
@Composable
fun SomedayLiquidGlassActionGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 2.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val contentColor = somedayLiquidGlassContentColor(SomedayLiquidGlassStyle.Regular, enabled = true)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .somedayLiquidGlass(
                    shape = LiquidCapsuleShape,
                    style = SomedayLiquidGlassStyle.Regular,
                    enabled = true,
                )
                .heightIn(min = SomedayLiquidGlassDefaults.ActionGroupHeight)
                .padding(horizontal = 2.dp),
            content = content,
        )
    }
}

/** Icon action intended for use inside [SomedayLiquidGlassActionGroup]. */
@Composable
fun SomedayLiquidGlassGroupedIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val style = if (emphasized) SomedayLiquidGlassStyle.Prominent else SomedayLiquidGlassStyle.Regular
    val contentColor = somedayLiquidGlassContentColor(style, enabled)
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.78f else 0.32f)
    } else {
        Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Prominent circular action used for the app's primary create action. */
@Composable
fun SomedayLiquidGlassFloatingActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SomedayLiquidGlassIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        enabled = enabled,
        style = SomedayLiquidGlassStyle.Prominent,
        size = SomedayLiquidGlassDefaults.FloatingActionButtonSize,
        iconSize = 24.dp,
        modifier = modifier,
    )
}

/** Layout for top navigation whose individual actions carry the glass material. */
@Composable
fun SomedayLiquidGlassTopBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(SomedayLiquidGlassDefaults.BottomBarHeight),
        content = content,
    )
}

/** Floating capsule navigation container for compact layouts. */
@Composable
fun SomedayLiquidGlassBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
    ) {
        SomedayLiquidGlassSurface(
            shape = LiquidCapsuleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(SomedayLiquidGlassDefaults.BottomBarHeight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .padding(4.dp),
                content = content,
            )
        }
    }
}

/** One labeled destination inside [SomedayLiquidGlassBottomBar]. */
@Composable
fun RowScope.SomedayLiquidGlassNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "Someday liquid navigation selection",
    )
    val selectedAlpha = if (colorScheme.background.luminance() < 0.5f) 0.18f else 0.12f
    val selectionColor = colorScheme.primary.copy(alpha = selectedAlpha * selectionProgress)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(LiquidCapsuleShape)
            .background(selectionColor)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    val selectedScale = 1f + 0.04f * selectionProgress
                    scaleX = selectedScale
                    scaleY = selectedScale
                },
        )
        Text(
            text = label,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Modifier.somedayLiquidGlass(
    shape: Shape,
    style: SomedayLiquidGlassStyle,
    enabled: Boolean,
    scale: Float = 1f,
): Modifier {
    val backdrop = LocalSomedayLiquidGlassBackdrop.current
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f
    val surfaceColor = somedayLiquidGlassSurfaceColor(style, isDark, enabled)
    val fallbackBrush = somedayLiquidGlassFallbackBrush(style, isDark, enabled)
    val borderBrush = somedayLiquidGlassBorderBrush(style, isDark, enabled)

    return if (backdrop == null) {
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
            .background(fallbackBrush, shape)
            .border(1.dp, borderBrush, shape)
    } else {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(SomedayLiquidGlassDefaults.BlurRadius.toPx())
                lens(
                    refractionHeight = SomedayLiquidGlassDefaults.RefractionHeight.toPx(),
                    refractionAmount = SomedayLiquidGlassDefaults.RefractionAmount.toPx(),
                    chromaticAberration = false,
                )
            },
            highlight = {
                Highlight.Default.copy(alpha = if (enabled) 0.86f else 0.34f)
            },
            shadow = {
                Shadow(
                    radius = 12.dp,
                    color = Color.Black.copy(alpha = if (enabled) 0.10f else 0.04f),
                )
            },
            layerBlock = {
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = {
                if (style == SomedayLiquidGlassStyle.Prominent) {
                    drawRect(surfaceColor, blendMode = BlendMode.Hue)
                }
                drawRect(surfaceColor)
            },
        )
    }
}

@Composable
private fun somedayLiquidGlassContentColor(
    style: SomedayLiquidGlassStyle,
    enabled: Boolean,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val color = when (style) {
        SomedayLiquidGlassStyle.Regular,
        SomedayLiquidGlassStyle.Clear,
        -> colorScheme.onSurface
        SomedayLiquidGlassStyle.Prominent -> colorScheme.onPrimary
    }
    return color.copy(alpha = if (enabled) 0.94f else 0.38f)
}

@Composable
private fun somedayLiquidGlassSurfaceColor(
    style: SomedayLiquidGlassStyle,
    isDark: Boolean,
    enabled: Boolean,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val enabledScale = if (enabled) 1f else 0.42f
    return when (style) {
        SomedayLiquidGlassStyle.Regular -> colorScheme.surface.copy(
            alpha = (if (isDark) 0.16f else 0.24f) * enabledScale,
        )
        SomedayLiquidGlassStyle.Prominent -> colorScheme.primary.copy(alpha = 0.74f * enabledScale)
        SomedayLiquidGlassStyle.Clear -> Color.White.copy(
            alpha = (if (isDark) 0.06f else 0.10f) * enabledScale,
        )
    }
}

@Composable
private fun somedayLiquidGlassFallbackBrush(
    style: SomedayLiquidGlassStyle,
    isDark: Boolean,
    enabled: Boolean,
): Brush {
    val colorScheme = MaterialTheme.colorScheme
    val enabledScale = if (enabled) 1f else 0.42f
    val colors = when (style) {
        SomedayLiquidGlassStyle.Regular -> listOf(
            Color.White.copy(alpha = (if (isDark) 0.14f else 0.58f) * enabledScale),
            colorScheme.surface.copy(alpha = (if (isDark) 0.24f else 0.38f) * enabledScale),
            colorScheme.secondaryContainer.copy(alpha = 0.22f * enabledScale),
        )
        SomedayLiquidGlassStyle.Prominent -> listOf(
            colorScheme.primary.copy(alpha = 0.92f * enabledScale),
            colorScheme.primary.copy(alpha = 0.76f * enabledScale),
        )
        SomedayLiquidGlassStyle.Clear -> listOf(
            Color.White.copy(alpha = (if (isDark) 0.08f else 0.18f) * enabledScale),
            colorScheme.surface.copy(alpha = 0.08f * enabledScale),
        )
    }
    return Brush.linearGradient(colors)
}

@Composable
private fun somedayLiquidGlassBorderBrush(
    style: SomedayLiquidGlassStyle,
    isDark: Boolean,
    enabled: Boolean,
): Brush {
    val colorScheme = MaterialTheme.colorScheme
    val enabledScale = if (enabled) 1f else 0.4f
    val accent = if (style == SomedayLiquidGlassStyle.Prominent) colorScheme.onPrimary else colorScheme.primary
    return Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = (if (isDark) 0.28f else 0.54f) * enabledScale),
            colorScheme.outlineVariant.copy(alpha = 0.26f * enabledScale),
            accent.copy(alpha = 0.14f * enabledScale),
        ),
    )
}
