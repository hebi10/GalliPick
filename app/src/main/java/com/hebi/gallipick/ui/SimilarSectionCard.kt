package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SimilarSectionCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(GalliUiMetrics.cardCorner)
    Column(
        modifier.fillMaxWidth()
            .background(if (emphasized) Panel else AppSurface, shape)
            .border(1.dp, Line, shape)
            .padding(GalliUiMetrics.cardPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
