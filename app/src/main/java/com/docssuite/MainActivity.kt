package com.docssuite

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.docssuite.converter.ui.FileConverterScreen
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.TextDocument
import com.docssuite.library.LibraryScreen
import com.docssuite.backup.BackupScreen
import com.docssuite.ocr.OcrScreen
import com.docssuite.recorder.RecorderScreen
import com.docssuite.tasks.TasksScreen
import com.docssuite.reader.ReaderScreen
import com.docssuite.core.DocType
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.media.MediaPlayerScreen
import com.docssuite.pdf.PdfPayload
import com.docssuite.pdf.PdfViewerScreen
import com.docssuite.pdftools.ui.PdfToolsScreen
import com.docssuite.pdftools.ui.PdfSignScreen
import com.docssuite.presentation.PresentationScreen
import com.docssuite.scanner.ui.ScannerScreen
import com.docssuite.spreadsheet.SpreadsheetScreen
import com.docssuite.texteditor.TextEditorScreen
import com.docssuite.texteditor.saveImportedTextDocument
import com.docssuite.transfer.ui.TransferScreen
import com.docssuite.ui.theme.DocsSuiteTheme

class MainActivity : ComponentActivity() {

    /**
     * Fichier reçu d'une autre application (« Ouvrir avec »). C'est un état
     * d'activité et non de composable : l'intent peut aussi arriver alors que
     * l'app tourne déjà.
     */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = uriFrom(intent)
        setContent {
            DocsSuiteTheme {
                DocsSuiteApp(
                    incomingFile = incoming,
                    onIncomingHandled = { incoming = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        uriFrom(intent)?.let { incoming = it }
    }

    @Suppress("DEPRECATION")
    private fun uriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
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
fun DocsSuiteApp(
    incomingFile: Uri? = null,
    onIncomingHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }

    // Un PDF se transmet par ses octets, pas par un argument de navigation :
    // une route ne peut pas porter un fichier.
    var pdfPayload by remember { mutableStateOf<PdfPayload?>(null) }

    fun openPdf(payload: PdfPayload?) {
        pdfPayload = payload
        navController.navigate("pdf")
    }

    fun openExtractedText(name: String, text: String) {
        val document = TextDocument(
            name,
            text.split('\n').map { TextParagraph(listOf(TextRun(it))) }
        )
        val id = saveImportedTextDocument(storage, document, name)
        navController.navigate(route("text_editor", id))
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenTextEditor = { navController.navigate(route("text_editor", it)) },
                onOpenSpreadsheet = { navController.navigate(route("spreadsheet", it)) },
                onOpenPresentation = { navController.navigate(route("presentation", it)) },
                onOpenPdf = ::openPdf,
                onOpenMedia = { navController.navigate("media") },
                onOpenConverter = { navController.navigate("converter") },
                onOpenTransfer = { navController.navigate("transfer") },
                onOpenScanner = { navController.navigate("scanner") },
                onOpenPdfTools = { navController.navigate("pdftools") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenBackup = { navController.navigate("backup") },
                onOpenOcr = { navController.navigate("ocr") },
                onOpenRecorder = { navController.navigate("recorder") },
                onOpenTasks = { navController.navigate("tasks") },
                onOpenPdfSign = { navController.navigate("pdfsign") },
                onOpenReader = { navController.navigate("reader") },
                incomingFile = incomingFile,
                onIncomingHandled = onIncomingHandled
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
        composable("pdf") {
            PdfViewerScreen(
                onBack = { navController.popBackStack() },
                initialFile = pdfPayload,
                onOpenAsDocument = ::openExtractedText
            )
        }
        composable("media") {
            MediaPlayerScreen(onBack = { navController.popBackStack() })
        }
        composable("converter") {
            FileConverterScreen(onBack = { navController.popBackStack() })
        }
        composable("transfer") {
            TransferScreen(onBack = { navController.popBackStack() })
        }
        composable("pdfsign") {
            PdfSignScreen(
                onBack = { navController.popBackStack() },
                onOpenPdf = { name, bytes -> openPdf(PdfPayload(name, bytes)) }
            )
        }
        composable("reader") {
            ReaderScreen(onBack = { navController.popBackStack() })
        }
        composable("tasks") {
            TasksScreen(onBack = { navController.popBackStack() })
        }
        composable("recorder") {
            RecorderScreen(
                onBack = { navController.popBackStack() },
                onCreateDocument = { title, text -> openExtractedText(title, text) }
            )
        }
        composable("ocr") {
            OcrScreen(
                onBack = { navController.popBackStack() },
                onCreateDocument = { title, text -> openExtractedText(title, text) }
            )
        }
        composable("backup") {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        composable("library") {
            LibraryScreen(
                onBack = { navController.popBackStack() },
                onOpen = { meta ->
                    val base = when (meta.type) {
                        DocType.TEXT -> "text_editor"
                        DocType.SHEET -> "spreadsheet"
                        DocType.DECK -> "presentation"
                    }
                    navController.navigate(route(base, meta.id))
                }
            )
        }
        composable("pdftools") {
            PdfToolsScreen(
                onBack = { navController.popBackStack() },
                onOpenPdf = { name, bytes -> openPdf(PdfPayload(name, bytes)) }
            )
        }
        composable("scanner") {
            ScannerScreen(
                onBack = { navController.popBackStack() },
                onOpenPdf = { name, bytes -> openPdf(PdfPayload(name, bytes)) }
            )
        }
    }
}
