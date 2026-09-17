package com.docssuite.spreadsheet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val ROWS = 10
private const val COLS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpreadsheetScreen(onBack: () -> Unit) {
    val cells = remember { mutableStateMapOf<String, String>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tableur") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items((0 until ROWS).toList()) { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(2.dp)) {
                    for (col in 0 until COLS) {
                        val key = "$row-$col"
                        OutlinedTextField(
                            value = cells[key] ?: "",
                            onValueChange = { cells[key] = it },
                            modifier = Modifier.weight(1f).padding(2.dp),
                            singleLine = true,
                            label = if (row == 0) {
                                { Text(('A' + col).toString()) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}
