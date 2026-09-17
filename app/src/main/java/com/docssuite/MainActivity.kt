package com.docssuite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

private fun route(base: String, docId: String?): String =
    if (docId == null) base else "$base?doc=$docId"

private fun docArgument() = listOf(
    navArgument("doc") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
)

@Composable
fun DocsSuiteApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenTextEditor = { navController.navigate(route("text_editor", it)) },
                onOpenSpreadsheet = { navController.navigate(route("spreadsheet", it)) },
                onOpenPresentation = { navController.navigate(route("presentation", it)) }
            )
        }
        composable("text_editor?doc={doc}", arguments = docArgument()) { entry ->
            TextEditorScreen(
                onBack = { navController.popBackStack() },
                initialDocId = entry.arguments?.getString("doc")
            )
        }
        composable("spreadsheet?doc={doc}", arguments = docArgument()) { entry ->
            SpreadsheetScreen(
                onBack = { navController.popBackStack() },
                initialDocId = entry.arguments?.getString("doc")
            )
        }
        composable("presentation?doc={doc}", arguments = docArgument()) { entry ->
            PresentationScreen(
                onBack = { navController.popBackStack() },
                initialDocId = entry.arguments?.getString("doc")
            )
        }
    }
}
