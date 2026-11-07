package com.qwantarc.messenger.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A pop-up menu like the iPhone's: a rounded card, each choice's name on
 * the left and its icon on the right, with hairlines between them.
 */
@Composable
fun PopMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = Modifier.width(250.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        content = content,
    )
}

/**
 * One choice in a [PopMenu]. Every choice but the [first] has a hairline
 * above it. A [destructive] one is red, as on iPhone.
 */
@Composable
fun PopMenuItem(
    text: String,
    icon: ImageVector? = null,
    first: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val color = when {
        destructive -> Color(0xFFFF3B30)
        else -> MaterialTheme.colorScheme.onSurface
    }
    DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp) },
        trailingIcon = icon?.let { { Icon(it, contentDescription = null, Modifier.size(21.dp)) } },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = MenuDefaults.itemColors(textColor = color, trailingIconColor = color),
        modifier = if (first) Modifier else Modifier.drawBehind {
            drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
        },
    )
}
