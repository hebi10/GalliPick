package com.hebi.gallipick.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun FilterGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterSection(title)
        content()
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun FilterSection(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
internal fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(GalliUiMetrics.buttonCorner)
    Text(
        text = label,
        color = if (selected) colors.onPrimary else colors.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.sizeIn(minHeight = GalliUiMetrics.touchTarget)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .clip(shape)
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else Line, shape)
            .plainClick(action = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
    )
}

@Composable
internal fun FilterBounds(
    minimum: String, onMinimum: (String) -> Unit,
    maximum: String, onMaximum: (String) -> Unit,
    minimumLabel: String, maximumLabel: String,
) {
    FilterFieldPair(
        first = { fieldModifier ->
            OutlinedTextField(
                value = minimum, onValueChange = onMinimum, label = { Text(minimumLabel) },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = fieldModifier,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        second = { fieldModifier ->
            OutlinedTextField(
                value = maximum, onValueChange = onMaximum, label = { Text(maximumLabel) },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = fieldModifier,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
    )
}

@Composable
internal fun FilterFieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 280.dp * fontScale.coerceAtLeast(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun <T> FilterDropdown(label: String, valueLabel: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = when {
                    anchorBounds.bottom + popupContentSize.height <= windowSize.height -> anchorBounds.bottom
                    anchorBounds.top >= popupContentSize.height -> anchorBounds.top - popupContentSize.height
                    else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
        Box {
            ActionButton("$valueLabel  ▾", onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width })
            if (expanded) {
                Popup(popupPositionProvider = position, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp), shadowElevation = 0.dp, tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.width(with(density) { anchorWidth.toDp() }).heightIn(max = 340.dp),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            options.forEach { (value, title) ->
                                Text(title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().semantics { selected = valueLabel == title; role = Role.RadioButton }
                                        .background(if (valueLabel == title) Panel else MaterialTheme.colorScheme.surface)
                                        .plainClick { onSelect(value); expanded = false }.padding(horizontal = 16.dp, vertical = 14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun numberText(value: Long?, divisor: Double): String {
    if (value == null) return ""
    return java.math.BigDecimal.valueOf(value).divide(java.math.BigDecimal.valueOf(divisor), 6, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}

internal fun parseBound(text: String, multiplier: Double, label: String, original: Long? = null): Long? {
    if (text.isBlank()) return null
    // The displayed decimal is shortened for editing; an untouched persisted value stays exact.
    if (original != null && original >= 0 && text == numberText(original, multiplier)) return original
    val number = text.trim().toDoubleOrNull()
    require(number != null && number.isFinite() && number >= 0) { "$label: 0 이상의 숫자를 입력해 주세요." }
    val scaled = number * multiplier
    require(scaled.isFinite() && scaled < Long.MAX_VALUE.toDouble()) { "$label 값이 너무 큽니다." }
    return scaled.toLong()
}

internal fun checkOrder(minimum: Long?, maximum: Long?, label: String) {
    require(minimum == null || maximum == null || minimum <= maximum) { "$label: 최소값이 최대값보다 클 수 없습니다." }
}
