package com.frameflow.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ExtendedFloatingActionButton(
    onClick: () -> Unit,
    text: @Composable () -> Unit
) {
    androidx.compose.material3.ExtendedFloatingActionButton(onClick = onClick) {
        text()
    }
}

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    supportingText: @Composable () -> Unit,
    onClick: () -> Unit
) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Column {
                text()
                supportingText()
            }
        },
        onClick = onClick
    )
}
