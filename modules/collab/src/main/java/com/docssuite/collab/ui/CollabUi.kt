package com.docssuite.collab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docssuite.collab.cloud.MailState
import com.docssuite.collab.cloud.PendingInvite
import com.docssuite.collab.cloud.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** La couleur de l'édition partagée. */
internal val Accent = Color(0xFF0EA5E9)

/** Une couleur par personne, bien distinctes, lisibles sur fond clair comme sombre. */
internal val PeopleColors = listOf(
    Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF059669), Color(0xFFD97706),
    Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFF0891B2), Color(0xFF65A30D),
)

internal fun personColor(index: Int): Color = PeopleColors[index.mod(PeopleColors.size)]

/** Les initiales d'un nom : « Léa Martin » → « LM ». */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Une pastille ronde aux initiales, dans la couleur de la personne. */
@Composable
internal fun Avatar(name: String, color: Color, size: Dp = 36.dp, description: String? = null) {
    Box(
        Modifier
            .size(size)
            .background(color, CircleShape)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

internal fun roleColor(role: Role): Color = when (role) {
    Role.OWNER -> Color(0xFF7C3AED)
    Role.EDITOR -> Color(0xFF2563EB)
    Role.COMMENTER -> Color(0xFFD97706)
    Role.READER -> Color(0xFF64748B)
}

/** Le rôle, en petite étiquette colorée. */
@Composable
internal fun RoleChip(role: Role, modifier: Modifier = Modifier) {
    val color = roleColor(role)
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f), modifier = modifier) {
        Text(
            role.label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Un encadré d'explication. */
@Composable
internal fun Explanation(title: String, text: String, color: Color = Accent, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = color.copy(alpha = 0.08f), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** « à l'instant », « il y a 5 min », « hier à 14:02 », « 3 oct. ». */
internal fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - then) / 60_000
    return when {
        then <= 0 -> ""
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 && sameDay(then, now) -> "aujourd'hui à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(then))
        sameDay(then, now - 24 * 3_600_000) -> "hier à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(then))
        else -> SimpleDateFormat("d MMM", Locale.FRANCE).format(Date(then))
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val format = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
    return format.format(Date(a)) == format.format(Date(b))
}

/** Où en est l'e-mail d'une invitation, en mots. */
internal fun mailStatus(invite: PendingInvite, now: Long): Pair<String, Boolean> = when (invite.mail) {
    MailState.SENT -> "E-mail d'invitation envoyé" to false
    MailState.SENDING -> if (invite.mailAt != null && now - invite.mailAt > 120_000) "L'e-mail n'est pas parti" to true else "E-mail en cours d'envoi…" to false
    MailState.FAILED -> "L'e-mail n'a pas pu partir" to true
    MailState.QUOTA -> "Limite d'e-mails du jour atteinte" to true
    MailState.REFUSED -> "E-mail non envoyé" to true
    null -> if (now - invite.at > 60_000) "Pas d'e-mail envoyé : le service d'envoi n'est pas installé" to true else "E-mail en préparation…" to false
}

/** Une ligne d'information avec une pastille de couleur. */
@Composable
internal fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
