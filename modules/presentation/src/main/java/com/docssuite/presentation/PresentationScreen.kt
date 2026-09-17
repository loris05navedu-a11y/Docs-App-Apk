package com.docssuite.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class Slide(var text: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentationScreen(onBack: () -> Unit) {
    val slides = remember { mutableStateListOf(Slide("Titre de la présentation")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Présentation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { slides.add(Slide("")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter une slide")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(slides.size) { index ->
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Slide ${index + 1}", style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { if (slides.size > 1) slides.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                            }
                        }
                        OutlinedTextField(
                            value = slides[index].text,
                            onValueChange = { slides[index] = slides[index].copy(text = it) },
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            placeholder = { Text("Contenu de la slide...") }
                        )
                    }
                }
            }
        }
    }
}
