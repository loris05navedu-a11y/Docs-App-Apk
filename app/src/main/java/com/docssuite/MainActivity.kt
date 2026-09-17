package com.docssuite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.docssuite.presentation.PresentationScreen
import com.docssuite.spreadsheet.SpreadsheetScreen
import com.docssuite.texteditor.TextEditorScreen
import com.docssuite.ui.theme.DocsSuiteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DocsSuiteTheme {
                DocsSuiteApp()
            }
        }
    }
}

@Composable
fun DocsSuiteApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenTextEditor = { navController.navigate("text_editor") },
                onOpenSpreadsheet = { navController.navigate("spreadsheet") },
                onOpenPresentation = { navController.navigate("presentation") }
            )
        }
        composable("text_editor") { TextEditorScreen(onBack = { navController.popBackStack() }) }
        composable("spreadsheet") { SpreadsheetScreen(onBack = { navController.popBackStack() }) }
        composable("presentation") { PresentationScreen(onBack = { navController.popBackStack() }) }
    }
}
