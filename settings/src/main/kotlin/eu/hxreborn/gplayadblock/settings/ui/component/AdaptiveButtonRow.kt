@file:Suppress("ktlint:standard:function-naming")

package eu.hxreborn.gplayadblock.settings.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> AdaptiveButtonRow(
    items: List<T>,
    modifier: Modifier = Modifier,
    button: @Composable (index: Int, item: T, stacked: Boolean, modifier: Modifier) -> Unit,
) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val count = items.size
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween
        val slot = (constraints.maxWidth - gap.roundToPx() * (count - 1)) / count
        val probes =
            subcompose("probe") {
                items.forEachIndexed { index, item ->
                    Box(Modifier.clearAndSetSemantics {}) {
                        button(index, item, false, Modifier)
                    }
                }
            }
        val stacked = probes.any { it.maxIntrinsicWidth(constraints.maxHeight) > slot }
        val content =
            subcompose(stacked) {
                if (stacked) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items.forEachIndexed { index, item ->
                            button(index, item, true, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        items.forEachIndexed { index, item ->
                            button(index, item, false, Modifier.weight(1f))
                        }
                    }
                }
            }.first().measure(constraints)
        layout(content.width, content.height) { content.place(0, 0) }
    }
}
