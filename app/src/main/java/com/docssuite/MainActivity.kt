package com.docssuite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bienvenue dans DocsApp Suite")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { }) {
            Text("✏️  Éditeur de Texte")
        }
        Button(onClick = { }) {
            Text("📊 Tableur")
        }
        Button(onClick = { }) {
            Text("📈 Présentation")
        }
    }
}
