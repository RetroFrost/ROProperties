package com.frameflow.app

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
